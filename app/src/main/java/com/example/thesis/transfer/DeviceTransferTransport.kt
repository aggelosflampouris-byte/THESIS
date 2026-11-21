package com.example.thesis.transfer

import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.util.CryptoUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

class ReceivedTransferBundle(
    val walletAddress: String,
    val identityPrivateKey: ByteArray,
    val nicknames: NicknameSnapshot,
    val seenMessageIds: List<String>,
    val baselineEstablished: Boolean,
    val archiveFile: File?,
    val archiveKey: ByteArray?
) {
    fun wipe() {
        identityPrivateKey.fill(0x00)
        archiveKey?.fill(0x00)
    }
}

data class TransferImportResult(
    val ok: Boolean,
    val newInstallationId: String? = null,
    val importedHistory: Boolean = false,
    val revokedOldInstallations: Boolean = false,
    val errorMessage: String? = null
)

class DeviceTransferServer(
    hosts: List<String>,
    allowLoopbackBind: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val sessionKey: ByteArray = ByteArray(DeviceTransferProtocol.SESSION_KEY_BYTES).apply {
        CryptoUtils.secureRandom.nextBytes(this)
    }
    private val serverSocket: ServerSocket = ServerSocket().apply {
        soTimeout = ACCEPT_TIMEOUT_MS
        if (allowLoopbackBind && hosts == listOf("127.0.0.1")) {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        } else {
            bind(InetSocketAddress(0))
        }
    }

    @Volatile
    private var activeClientSocket: Socket? = null

    val pairingInfo: DeviceTransferPairingInfo = DeviceTransferProtocol.buildPairingUri(
        hosts = hosts,
        port = serverSocket.localPort,
        sessionKey = sessionKey,
        allowLoopback = allowLoopbackBind
    )

    fun cancel() {
        runCatching { activeClientSocket?.close() }
        runCatching { serverSocket.close() }
    }

    suspend fun acceptAndReceive(
        tempDir: File,
        onProgress: (Float, String) -> Unit,
        onApplyImport: suspend (ReceivedTransferBundle) -> TransferImportResult
    ): TransferImportResult = withContext(ioDispatcher) {
        val s2rKey = DeviceTransferCrypto.deriveSubkey(sessionKey, DeviceTransferProtocol.LABEL_S2R)
        val r2sKey = DeviceTransferCrypto.deriveSubkey(sessionKey, DeviceTransferProtocol.LABEL_R2S)
        val archiveKey = DeviceTransferCrypto.deriveSubkey(sessionKey, DeviceTransferProtocol.LABEL_ARCHIVE)
        val nonce = ByteArray(DeviceTransferProtocol.NONCE_BYTES).apply {
            CryptoUtils.secureRandom.nextBytes(this)
        }
        var incomingArchive: File? = null
        var envelope: TransferMetadataEnvelope? = null
        var bundle: ReceivedTransferBundle? = null
        var client: Socket? = null

        try {
            client = serverSocket.accept()
            activeClientSocket = client
            client.soTimeout = SOCKET_IO_TIMEOUT_MS

            val out = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            val inp = DataInputStream(BufferedInputStream(client.getInputStream()))

            // Enviamos un reto aleatorio antes de aceptar claves para evitar reproduccion o suplantacion sin QR.
            DeviceTransferCrypto.writeEncryptedFrame(
                out = out,
                key = r2sKey,
                frameType = DeviceTransferProtocol.FRAME_HELLO_CHALLENGE,
                seq = 0L,
                plaintext = nonce
            )
            out.flush()
            onProgress(0.08f, "Συνδέθηκε η παλιά συσκευή — έλεγχος ταυτότητας...")

            val metaFrame = DeviceTransferCrypto.readEncryptedFrame(
                input = inp,
                key = s2rKey,
                expectedFrameType = DeviceTransferProtocol.FRAME_METADATA_AND_KEYS,
                expectedSeq = 0L,
                maxPlaintextBytes = DeviceTransferProtocol.MAX_METADATA_BYTES
            )
            val parsedMeta = TransferMetadataEnvelope.decode(metaFrame.payload)
            envelope = parsedMeta
            if (!MessageDigest.isEqual(parsedMeta.challengeNonce, nonce)) {
                throw SecurityException("Challenge nonce mismatch")
            }

            var nextSeq = 1L
            if (parsedMeta.hasArchive) {
                if (parsedMeta.archiveByteLength <= 0L ||
                    parsedMeta.archiveByteLength > DeviceTransferProtocol.MAX_ARCHIVE_BYTES
                ) {
                    throw SecurityException("Invalid archive byte length: ${parsedMeta.archiveByteLength}")
                }
                val expectedHash = parsedMeta.archiveSha256Hex?.takeIf { it.length == 64 }
                    ?: throw SecurityException("Missing archive SHA-256 digest")

                val archiveFile = TransferFileHygiene.createTempTransferFile(tempDir, "incoming_archive")
                incomingArchive = archiveFile
                val sha256 = MessageDigest.getInstance("SHA-256")
                var totalReceived = 0L

                FileOutputStream(archiveFile).use { fos ->
                    while (totalReceived < parsedMeta.archiveByteLength) {
                        val chunkFrame = DeviceTransferCrypto.readEncryptedFrame(
                            input = inp,
                            key = s2rKey,
                            expectedFrameType = DeviceTransferProtocol.FRAME_ARCHIVE_CHUNK,
                            expectedSeq = nextSeq++,
                            maxPlaintextBytes = DeviceTransferProtocol.CHUNK_SIZE_BYTES
                        )
                        if (totalReceived + chunkFrame.payload.size > parsedMeta.archiveByteLength) {
                            throw SecurityException("Received archive exceeds declared length")
                        }
                        fos.write(chunkFrame.payload)
                        sha256.update(chunkFrame.payload)
                        totalReceived += chunkFrame.payload.size
                        val ratio = totalReceived.toFloat() / parsedMeta.archiveByteLength.toFloat()
                        onProgress(0.15f + 0.65f * ratio, "Λήψη κρυπτογραφημένου ιστορικού MLS...")
                    }
                    fos.fd.sync()
                }

                val finishFrame = DeviceTransferCrypto.readEncryptedFrame(
                    input = inp,
                    key = s2rKey,
                    expectedFrameType = DeviceTransferProtocol.FRAME_TRANSFER_FINISH,
                    expectedSeq = nextSeq,
                    maxPlaintextBytes = 1024
                )
                val actualHex = sha256.digest().toHexLower()
                val (finishBytes, finishHash) = decodeFinishPayload(finishFrame.payload)
                if (finishBytes != totalReceived ||
                    !actualHex.equals(expectedHash, ignoreCase = true) ||
                    !actualHex.equals(finishHash, ignoreCase = true)
                ) {
                    throw SecurityException("Archive SHA-256 verification failed before import")
                }
            } else {
                val finishFrame = DeviceTransferCrypto.readEncryptedFrame(
                    input = inp,
                    key = s2rKey,
                    expectedFrameType = DeviceTransferProtocol.FRAME_TRANSFER_FINISH,
                    expectedSeq = nextSeq,
                    maxPlaintextBytes = 1024
                )
                val (finishBytes, _) = decodeFinishPayload(finishFrame.payload)
                if (finishBytes != 0L) {
                    throw SecurityException("Unexpected archive length on metadata-only transfer")
                }
            }

            onProgress(0.85f, "Αποθήκευση στο Hardware KeyStore & εισαγωγή MLS...")
            val activeBundle = ReceivedTransferBundle(
                walletAddress = parsedMeta.walletAddress,
                identityPrivateKey = parsedMeta.identityPrivateKey.copyOf(),
                nicknames = parsedMeta.nicknames,
                seenMessageIds = parsedMeta.seenMessageIds,
                baselineEstablished = parsedMeta.baselineEstablished,
                archiveFile = incomingArchive,
                archiveKey = if (incomingArchive != null) archiveKey.copyOf() else null
            )
            bundle = activeBundle

            val importResult = try {
                onApplyImport(activeBundle)
            } catch (e: Exception) {
                TransferImportResult(
                    ok = false,
                    errorMessage = e.message ?: "Import failed on receiver"
                )
            }

            val ack = TransferAckEnvelope(
                ok = importResult.ok,
                newInstallationId = importResult.newInstallationId,
                importedHistory = importResult.importedHistory,
                revokedOldInstallations = importResult.revokedOldInstallations,
                errorMessage = importResult.errorMessage
            )
            DeviceTransferCrypto.writeEncryptedFrame(
                out = out,
                key = r2sKey,
                frameType = DeviceTransferProtocol.FRAME_IMPORT_ACK,
                seq = 1L,
                plaintext = ack.encode()
            )
            out.flush()
            onProgress(1.0f, "Ολοκληρώθηκε")
            importResult
        } finally {
            s2rKey.fill(0x00)
            r2sKey.fill(0x00)
            archiveKey.fill(0x00)
            nonce.fill(0x00)
            sessionKey.fill(0x00)
            pairingInfo.wipe()
            envelope?.wipe()
            bundle?.wipe()
            TransferFileHygiene.shredAndDelete(incomingArchive)
            runCatching { client?.close() }
            runCatching { serverSocket.close() }
        }
    }

    companion object {
        private const val ACCEPT_TIMEOUT_MS = 180_000
        private const val SOCKET_IO_TIMEOUT_MS = 30_000
    }
}

class DeviceTransferClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun send(
        pairingInfo: DeviceTransferPairingInfo,
        walletAddress: String,
        identityPrivateKey: ByteArray,
        nicknames: NicknameSnapshot,
        seenMessageIds: List<String>,
        baselineEstablished: Boolean,
        archiveFile: File?,
        onProgress: (Float, String) -> Unit
    ): TransferImportResult = withContext(ioDispatcher) {
        val s2rKey = DeviceTransferCrypto.deriveSubkey(pairingInfo.sessionKey, DeviceTransferProtocol.LABEL_S2R)
        val r2sKey = DeviceTransferCrypto.deriveSubkey(pairingInfo.sessionKey, DeviceTransferProtocol.LABEL_R2S)
        var envelope: TransferMetadataEnvelope? = null
        var socket: Socket? = null

        try {
            onProgress(0.18f, "Σύνδεση με τη νέα συσκευή...")
            socket = connectFirstReachable(pairingInfo.hosts, pairingInfo.port)
            socket.soTimeout = SOCKET_IO_TIMEOUT_MS

            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))

            val challengeFrame = DeviceTransferCrypto.readEncryptedFrame(
                input = inp,
                key = r2sKey,
                expectedFrameType = DeviceTransferProtocol.FRAME_HELLO_CHALLENGE,
                expectedSeq = 0L,
                maxPlaintextBytes = DeviceTransferProtocol.NONCE_BYTES
            )
            if (challengeFrame.payload.size != DeviceTransferProtocol.NONCE_BYTES) {
                throw SecurityException("Invalid challenge nonce length")
            }

            val validArchive = archiveFile?.takeIf { it.exists() && it.isFile && it.length() > 0L }
            val archiveLen = validArchive?.length() ?: 0L
            val archiveSha = validArchive?.let { computeFileSha256Hex(it) }

            val metadata = TransferMetadataEnvelope(
                challengeNonce = challengeFrame.payload.copyOf(),
                walletAddress = walletAddress,
                identityPrivateKey = identityPrivateKey.copyOf(),
                nicknames = nicknames,
                seenMessageIds = seenMessageIds,
                baselineEstablished = baselineEstablished,
                hasArchive = validArchive != null,
                archiveByteLength = archiveLen,
                archiveSha256Hex = archiveSha
            )
            envelope = metadata

            onProgress(0.25f, "Αποστολή κλειδιών ταυτότητας και προτιμήσεων...")
            DeviceTransferCrypto.writeEncryptedFrame(
                out = out,
                key = s2rKey,
                frameType = DeviceTransferProtocol.FRAME_METADATA_AND_KEYS,
                seq = 0L,
                plaintext = metadata.encode()
            )
            out.flush()

            var nextSeq = 1L
            if (validArchive != null) {
                val buf = ByteArray(DeviceTransferProtocol.CHUNK_SIZE_BYTES)
                var totalSent = 0L
                FileInputStream(validArchive).use { fis ->
                    while (true) {
                        val read = fis.read(buf)
                        if (read <= 0) break
                        val chunk = if (read == buf.size) buf else buf.copyOf(read)
                        DeviceTransferCrypto.writeEncryptedFrame(
                            out = out,
                            key = s2rKey,
                            frameType = DeviceTransferProtocol.FRAME_ARCHIVE_CHUNK,
                            seq = nextSeq++,
                            plaintext = chunk
                        )
                        totalSent += read
                        val ratio = totalSent.toFloat() / archiveLen.toFloat()
                        onProgress(0.25f + 0.60f * ratio, "Αποστολή κρυπτογραφημένου ιστορικού MLS...")
                    }
                }
                buf.fill(0x00)
                if (totalSent != archiveLen) {
                    throw IllegalStateException("Archive length changed while streaming")
                }
            }

            DeviceTransferCrypto.writeEncryptedFrame(
                out = out,
                key = s2rKey,
                frameType = DeviceTransferProtocol.FRAME_TRANSFER_FINISH,
                seq = nextSeq,
                plaintext = encodeFinishPayload(archiveLen, archiveSha)
            )
            out.flush()

            onProgress(0.92f, "Αναμονή επιβεβαίωσης από τη νέα συσκευή...")
            // Aumentamos el timeout mientras el receptor inicializa SQLCipher e importa el archivo MLS.
            socket.soTimeout = IMPORT_ACK_TIMEOUT_MS
            val ackFrame = DeviceTransferCrypto.readEncryptedFrame(
                input = inp,
                key = r2sKey,
                expectedFrameType = DeviceTransferProtocol.FRAME_IMPORT_ACK,
                expectedSeq = 1L,
                maxPlaintextBytes = 8 * 1024
            )
            val ack = TransferAckEnvelope.decode(ackFrame.payload)
            TransferImportResult(
                ok = ack.ok,
                newInstallationId = ack.newInstallationId,
                importedHistory = ack.importedHistory,
                revokedOldInstallations = ack.revokedOldInstallations,
                errorMessage = ack.errorMessage
            )
        } finally {
            s2rKey.fill(0x00)
            r2sKey.fill(0x00)
            envelope?.wipe()
            runCatching { socket?.close() }
        }
    }

    private fun connectFirstReachable(hosts: List<String>, port: Int): Socket {
        var lastError: Exception? = null
        for (host in hosts) {
            val candidate = Socket()
            try {
                candidate.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                return candidate
            } catch (e: Exception) {
                runCatching { candidate.close() }
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No reachable transfer host in pairing QR")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKET_IO_TIMEOUT_MS = 30_000
        private const val IMPORT_ACK_TIMEOUT_MS = 90_000
    }
}

private fun encodeFinishPayload(totalBytes: Long, sha256Hex: String?): ByteArray {
    val baos = ByteArrayOutputStream()
    DataOutputStream(baos).use { out ->
        out.writeLong(totalBytes)
        val hashBytes = (sha256Hex ?: "").toByteArray(Charsets.UTF_8)
        out.writeInt(hashBytes.size)
        out.write(hashBytes)
    }
    return baos.toByteArray()
}

private fun decodeFinishPayload(raw: ByteArray): Pair<Long, String> {
    DataInputStream(ByteArrayInputStream(raw)).use { inp ->
        val totalBytes = inp.readLong()
        val len = inp.readInt()
        if (len < 0 || len > 128) throw SecurityException("Invalid finish digest length: $len")
        val bytes = ByteArray(len).also { inp.readFully(it) }
        return totalBytes to bytes.toString(Charsets.UTF_8)
    }
}

private fun computeFileSha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(32 * 1024)
    FileInputStream(file).use { fis ->
        while (true) {
            val read = fis.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
    }
    return digest.digest().toHexLower()
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
