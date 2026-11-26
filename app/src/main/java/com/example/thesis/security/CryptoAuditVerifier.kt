package com.example.thesis.security

import android.os.Build
import android.os.SystemClock
import com.example.thesis.BuildConfig
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.util.CryptoUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.xmtp.android.library.Client
import org.xmtp.android.library.XMTPEnvironment
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CryptoAuditCheck(
    val id: String,
    val title: String,
    val passed: Boolean,
    val durationMs: Double,
    val details: String
)

data class Eip191AuditProof(
    val challenge: String,
    val walletAddress: String,
    val recoveredAddress: String,
    val signatureHex: String,
    val isLowS: Boolean,
    val verified: Boolean
)

data class DatabaseCiphertextAudit(
    val fileCount: Int,
    val totalBytes: Long,
    val hasPlaintextSqliteHeader: Boolean,
    val sampleSha256Prefix: String
)

data class CryptoAuditReport(
    val timestampMs: Long,
    val environmentName: String,
    val isDebugBuild: Boolean,
    val versionName: String,
    val versionCode: Int,
    val checks: List<CryptoAuditCheck>,
    val eip191Proof: Eip191AuditProof?
) {
    val passedCount: Int get() = checks.count { it.passed }
    val totalCount: Int get() = checks.size
    val allPassed: Boolean get() = checks.isNotEmpty() && passedCount == totalCount
}

// Ejecuta comprobaciones empiricas en vivo sobre Keystore, AEAD, EIP-191, SQLCipher y la red XMTP Production.
object CryptoAuditVerifier {
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val SQLITE_PLAINTEXT_HEADER = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val SECP256K1_HALF_N: BigInteger = Sign.CURVE_PARAMS.n.shiftRight(1)

    suspend fun runFullAudit(
        keystoreManager: KeystoreManager,
        databaseFactory: DatabaseFactory,
        signer: ThesisSigner?,
        client: Client?
    ): CryptoAuditReport = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val checks = ArrayList<CryptoAuditCheck>(7)
        var eip191Proof: Eip191AuditProof? = null

        val ksAudit = measureCheck(
            id = "keystore_kek",
            title = "Hardware Keystore KEK Isolation"
        ) {
            val info = keystoreManager.inspectHardwareKeystore()
            val ok = info.kekExists && info.isNonExportable
            val detail = "exists=${info.kekExists}, encodedNull=${info.isNonExportable}, level=${info.securityLevel}, envelope=${info.dekEnvelopePresent}"
            ok to detail
        }
        checks.add(ksAudit)

        val aeadAudit = measureCheck(
            id = "dek_aead",
            title = "DEK Unwrap & AES-256-GCM AEAD Tamper Proof"
        ) {
            when (val res = keystoreManager.getOrCreateDatabaseKey()) {
                is KeystoreResult.Success -> {
                    val dek = res.value
                    try {
                        val validLen = dek.size == KeystoreManager.DEK_SIZE_BYTES
                        val tamperRejected = verifyAeadTamperDetection(dek)
                        val ok = validLen && tamperRejected
                        ok to "dekBytes=${dek.size}, tamperRejected=$tamperRejected"
                    } finally {
                        dek.fill(0x00)
                    }
                }
                is KeystoreResult.Failure -> {
                    false to "DEK unwrap failed: ${res.message}"
                }
            }
        }
        checks.add(aeadAudit)

        val eip191Audit = measureCheck(
            id = "eip191_ecrecover",
            title = "Ethereum EIP-191 Secp256k1 Sign & ecrecover"
        ) {
            if (signer == null) {
                false to "No active ThesisSigner in session."
            } else {
                val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val challenge = "THESIS-AUDIT|${signer.address}|${isoFmt.format(Date(nowMs))}"
                val proof = verifyEip191SignatureProof(signer, challenge)
                eip191Proof = proof
                val ok = proof.verified && proof.isLowS
                ok to "addr=${proof.walletAddress}, recovered=${proof.recoveredAddress}, lowS=${proof.isLowS}, sig=${proof.signatureHex.take(18)}..."
            }
        }
        checks.add(eip191Audit)

        val mlsSignAudit = measureCheck(
            id = "xmtp_install_ed25519",
            title = "XMTP MLS Installation Key Ed25519 Verify"
        ) {
            if (client == null) {
                false to "XMTP Client is not Ready."
            } else {
                val challenge = "THESIS-MLS-LEAF-AUDIT|$nowMs"
                val sig = client.signWithInstallationKey(challenge)
                val validSig = client.verifySignatureWithInstallationId(challenge, sig, client.installationId)
                val tampered = sig.copyOf().apply {
                    if (isNotEmpty()) this[0] = (this[0].toInt() xor 0xFF).toByte()
                }
                val tamperedRejected = !client.verifySignatureWithInstallationId(challenge, tampered, client.installationId)
                val ok = validSig && tamperedRejected
                ok to "installId=${client.installationId.take(12)}..., validSig=$validSig, tamperedRejected=$tamperedRejected"
            }
        }
        checks.add(mlsSignAudit)

        val dbCipherAudit = measureCheck(
            id = "sqlcipher_disk",
            title = "On-Disk SQLCipher Ciphertext Integrity"
        ) {
            val ethAddr = signer?.address ?: client?.publicIdentity?.identifier
            val cleanAddr = ethAddr?.let { CryptoUtils.sanitiseEthereumAddress(it) }
            if (cleanAddr == null) {
                false to "No valid Ethereum account sandbox available."
            } else {
                val dir = databaseFactory.resolveAccountDatabaseDirectory(cleanAddr)
                val stats = inspectDatabaseEncryptionOnDisk(dir)
                val ok = stats.fileCount > 0 && !stats.hasPlaintextSqliteHeader
                ok to "dbFiles=${stats.fileCount}, totalBytes=${stats.totalBytes}, plaintextHeader=${stats.hasPlaintextSqliteHeader}, sha256=${stats.sampleSha256Prefix}"
            }
        }
        checks.add(dbCipherAudit)

        val xmtpLiveAudit = measureCheck(
            id = "xmtp_network_ledger",
            title = "Live XMTP Network & Identity Ledger"
        ) {
            if (client == null) {
                false to "XMTP Client is not Ready."
            } else {
                val remoteState = client.inboxState(refreshFromNetwork = true)
                val canMessageMap = client.canMessage(listOf(client.publicIdentity))
                val resolvedInbox = client.inboxIdFromIdentity(client.publicIdentity)
                val selfReachable = canMessageMap[client.publicIdentity.identifier]
                    ?: canMessageMap[client.publicIdentity.identifier.lowercase(Locale.ROOT)]
                    ?: false
                val inboxMatches = remoteState.inboxId.equals(client.inboxId, ignoreCase = true) &&
                    resolvedInbox.equals(client.inboxId, ignoreCase = true)
                val recoveryMatches = remoteState.recoveryPublicIdentity.identifier
                    .equals(client.publicIdentity.identifier, ignoreCase = true)
                val installPresent = remoteState.installations.any {
                    it.installationId.equals(client.installationId, ignoreCase = true)
                }
                val ok = selfReachable && inboxMatches && recoveryMatches && installPresent
                ok to "env=${client.environment.name}, inboxMatch=$inboxMatches, recoveryMatch=$recoveryMatches, installations=${remoteState.installations.size}, canMessage=$selfReachable"
            }
        }
        checks.add(xmtpLiveAudit)

        val postureAudit = measureCheck(
            id = "binary_posture",
            title = "Release Binary & Production Network Posture"
        ) {
            val envName = client?.environment?.name ?: "DISCONNECTED"
            val isProd = client?.environment == XMTPEnvironment.PRODUCTION
            val abis = Build.SUPPORTED_ABIS.joinToString(",")
            val detail = "env=$envName, debuggable=${BuildConfig.DEBUG}, version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), abis=[$abis]"
            isProd to detail
        }
        checks.add(postureAudit)

        CryptoAuditReport(
            timestampMs = nowMs,
            environmentName = client?.environment?.name ?: "DISCONNECTED",
            isDebugBuild = BuildConfig.DEBUG,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            checks = checks,
            eip191Proof = eip191Proof
        )
    }

    // Firma un reto con EIP-191 y recupera la direccion mediante ecrecover independiente para demostrar propiedad matematica.
    suspend fun verifyEip191SignatureProof(
        signer: ThesisSigner,
        challenge: String
    ): Eip191AuditProof {
        val rawSig = signer.signMessage(challenge)
        require(rawSig.size == 65) { "Expected 65-byte RSV signature, got ${rawSig.size}" }
        val r = rawSig.copyOfRange(0, 32)
        val s = rawSig.copyOfRange(32, 64)
        val v = byteArrayOf(rawSig[64])
        val sBigInt = BigInteger(1, s)
        val isLowS = sBigInt > BigInteger.ZERO && sBigInt <= SECP256K1_HALF_N

        val challengeBytes = challenge.toByteArray(StandardCharsets.UTF_8)
        val recoveredKey = Sign.signedPrefixedMessageToKey(challengeBytes, Sign.SignatureData(v, r, s))
        val recoveredAddress = "0x${Keys.getAddress(recoveredKey).lowercase(Locale.ROOT)}"
        val matches = recoveredAddress.equals(signer.address, ignoreCase = true)
        val sigHex = "0x" + rawSig.joinToString("") { "%02x".format(it) }

        return Eip191AuditProof(
            challenge = challenge,
            walletAddress = signer.address,
            recoveredAddress = recoveredAddress,
            signatureHex = sigHex,
            isLowS = isLowS,
            verified = matches && isLowS
        )
    }

    // Cifra con AES-256-GCM y comprueba que alterar un solo bit dispara AEADBadTagException.
    fun verifyAeadTamperDetection(keyBytes: ByteArray): Boolean {
        if (keyBytes.size != KeystoreManager.DEK_SIZE_BYTES) return false
        val iv = ByteArray(GCM_IV_BYTES)
        CryptoUtils.secureRandom.nextBytes(iv)
        val keySpec = SecretKeySpec(keyBytes.copyOf(), "AES")
        val payload = "THESIS_AEAD_SELFTEST_PAYLOAD".toByteArray(StandardCharsets.UTF_8)
        var ciphertext = ByteArray(0)
        return try {
            val enc = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            enc.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            ciphertext = enc.doFinal(payload)

            val decOk = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            decOk.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = decOk.doFinal(ciphertext)
            if (!plain.contentEquals(payload)) return false

            val tampered = ciphertext.copyOf()
            tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
            val decTamper = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            decTamper.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            try {
                decTamper.doFinal(tampered)
                false
            } catch (_: AEADBadTagException) {
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            iv.fill(0x00)
            ciphertext.fill(0x00)
        }
    }

    // Verifica que la base SQLite en disco no conserve la cabecera en texto claro "SQLite format 3".
    fun inspectDatabaseEncryptionOnDisk(accountDir: File): DatabaseCiphertextAudit {
        if (!accountDir.exists() || !accountDir.isDirectory) {
            return DatabaseCiphertextAudit(
                fileCount = 0,
                totalBytes = 0L,
                hasPlaintextSqliteHeader = false,
                sampleSha256Prefix = "none"
            )
        }
        val dbFiles = accountDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.length() > 0L &&
                    (file.name.endsWith(".db", ignoreCase = true) || file.name.endsWith(".db3", ignoreCase = true))
            }
            .toList()

        if (dbFiles.isEmpty()) {
            return DatabaseCiphertextAudit(
                fileCount = 0,
                totalBytes = 0L,
                hasPlaintextSqliteHeader = false,
                sampleSha256Prefix = "none"
            )
        }

        var hasPlaintextHeader = false
        var totalBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val sampleFile = dbFiles.first()

        for (file in dbFiles) {
            totalBytes += file.length()
            val header = ByteArray(SQLITE_PLAINTEXT_HEADER.size)
            val readLen = runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    raf.read(header)
                }
            }.getOrDefault(0)
            if (readLen == SQLITE_PLAINTEXT_HEADER.size && header.contentEquals(SQLITE_PLAINTEXT_HEADER)) {
                hasPlaintextHeader = true
            }
        }

        runCatching {
            sampleFile.inputStream().use { inp ->
                val buf = ByteArray(4096)
                val read = inp.read(buf)
                if (read > 0) digest.update(buf, 0, read)
            }
        }
        val shaPrefix = digest.digest().take(8).joinToString("") { "%02x".format(it) }

        return DatabaseCiphertextAudit(
            fileCount = dbFiles.size,
            totalBytes = totalBytes,
            hasPlaintextSqliteHeader = hasPlaintextHeader,
            sampleSha256Prefix = shaPrefix
        )
    }

    fun formatAuditReportMarkdown(report: CryptoAuditReport): String {
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return buildString {
            appendLine("## Empirical Cryptography & Network Self-Audit (${report.passedCount}/${report.totalCount} PASS)")
            appendLine("- **Timestamp (UTC)**: `${isoFmt.format(Date(report.timestampMs))}`")
            appendLine("- **XMTP Network**: `${report.environmentName}` | **Build**: `${if (report.isDebugBuild) "DEBUG" else "RELEASE"}` (`v${report.versionName}` / `${report.versionCode}`)")
            appendLine()
            appendLine("| Verification Check | Status | Latency (ms) | Empirical Evidence |")
            appendLine("| :--- | :---: | :---: | :--- |")
            report.checks.forEach { c ->
                val status = if (c.passed) "PASS" else "FAIL"
                val cleanDetails = c.details.replace("|", "/")
                appendLine(String.format(Locale.US, "| %s | %s | %.2f | %s |", c.title, status, c.durationMs, cleanDetails))
            }
            report.eip191Proof?.let { proof ->
                appendLine()
                appendLine("### External EIP-191 Signature Verification Proof")
                appendLine("Verify independently via Etherscan (`etherscan.io/verifySig`) or Foundry (`cast wallet verify`):")
                appendLine("```text")
                appendLine("Address   : ${proof.walletAddress}")
                appendLine("Recovered : ${proof.recoveredAddress}")
                appendLine("Challenge : ${proof.challenge}")
                appendLine("Signature : ${proof.signatureHex}")
                appendLine("Low-S     : ${proof.isLowS}")
                appendLine("Verified  : ${proof.verified}")
                appendLine("```")
            }
            appendLine()
            appendLine("---")
            appendLine()
        }
    }

    private inline fun measureCheck(
        id: String,
        title: String,
        block: () -> Pair<Boolean, String>
    ): CryptoAuditCheck {
        val startNs = SystemClock.elapsedRealtimeNanos()
        var passed = false
        var details: String
        try {
            val (ok, info) = block()
            passed = ok
            details = info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            passed = false
            details = "${e::class.java.simpleName}: ${e.message ?: "unexpected error"}"
        }
        val elapsedMs = ((SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0).coerceAtLeast(0.0)
        return CryptoAuditCheck(
            id = id,
            title = title,
            passed = passed,
            durationMs = elapsedMs,
            details = details
        )
    }
}
