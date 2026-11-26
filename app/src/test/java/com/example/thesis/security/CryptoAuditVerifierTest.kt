package com.example.thesis.security

import com.example.thesis.identity.ThesisSigner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Random

class CryptoAuditVerifierTest {

    @Test
    fun verifyEip191SignatureProof_signsAndRecoversMatchingAddressWithLowS() = runTest {
        val signer = ThesisSigner.createEphemeral()
        try {
            val challenge = "THESIS-AUDIT|${signer.address}|2026-11-26T13:00:00Z"
            val proof = CryptoAuditVerifier.verifyEip191SignatureProof(signer, challenge)

            assertTrue(proof.verified)
            assertTrue(proof.isLowS)
            assertEquals(signer.address.lowercase(), proof.recoveredAddress.lowercase())
            assertTrue(proof.signatureHex.startsWith("0x"))
            assertEquals(132, proof.signatureHex.length)
        } finally {
            signer.close()
        }
    }

    @Test
    fun verifyAeadTamperDetection_acceptsValidKeyAndRejectsWrongKeySize() {
        val validKey = ByteArray(KeystoreManager.DEK_SIZE_BYTES) { (it + 11).toByte() }
        assertTrue(CryptoAuditVerifier.verifyAeadTamperDetection(validKey))
        assertFalse(CryptoAuditVerifier.verifyAeadTamperDetection(ByteArray(16)))
    }

    @Test
    fun inspectDatabaseEncryptionOnDisk_rejectsShortAndFullPlaintextSqliteFiles() {
        val dir = Files.createTempDirectory("cipher-audit-plain").toFile()
        try {
            val shortPlain = dir.resolve("short.db")
            shortPlain.writeBytes("SQLite format 3\u0000remainder-bytes".toByteArray(StandardCharsets.US_ASCII))
            val shortAudit = CryptoAuditVerifier.inspectDatabaseEncryptionOnDisk(dir)
            assertEquals(1, shortAudit.fileCount)
            assertFalse(shortAudit.isEncrypted)
            assertEquals("PLAINTEXT_SQLITE", shortAudit.encryptionMode)

            shortPlain.delete()

            // Construimos una pagina SQLite 3 valida de 4096 bytes sin cifrar (ceros en bytes 72..91 y esquema en claro).
            val fullPlain = dir.resolve("plain.db3")
            val page = ByteArray(4096)
            "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(page, 0)
            page[59] = 1
            "CREATE TABLE groups (id BLOB)".toByteArray(StandardCharsets.US_ASCII).copyInto(page, 120)
            fullPlain.writeBytes(page)

            val fullAudit = CryptoAuditVerifier.inspectDatabaseEncryptionOnDisk(dir)
            assertEquals(1, fullAudit.fileCount)
            assertFalse(fullAudit.isEncrypted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inspectDatabaseEncryptionOnDisk_acceptsWal32AndFullSqlCipherDatabases() {
        val dir = Files.createTempDirectory("cipher-audit-wal32").toFile()
        try {
            // Simulamos una base SQLCipher 4 con cipher_plaintext_header_size=32 y archivo sqlcipher_salt.
            val wal32Db = dir.resolve("xmtp.db3")
            val rng = Random(20261126L)
            val wal32Bytes = ByteArray(4096)
            rng.nextBytes(wal32Bytes)
            "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(wal32Bytes, 0)
            wal32Bytes[16] = 0x10
            wal32Bytes[17] = 0x00
            wal32Db.writeBytes(wal32Bytes)
            dir.resolve("xmtp.db3.sqlcipher_salt").writeBytes(ByteArray(16) { 0x5A })

            val wal32Audit = CryptoAuditVerifier.inspectDatabaseEncryptionOnDisk(dir)
            assertEquals(1, wal32Audit.fileCount)
            assertTrue(wal32Audit.isEncrypted)
            assertEquals("SQLCIPHER_WAL32", wal32Audit.encryptionMode)
            assertTrue(wal32Audit.hasSaltFile)
            assertTrue(wal32Audit.payloadEntropyBits >= 7.5)

            // Probamos tambien el modo completamente cifrado (cipher_plaintext_header_size=0).
            dir.resolve("xmtp.db3.sqlcipher_salt").delete()
            wal32Db.delete()
            val fullCipherBytes = ByteArray(4096)
            rng.nextBytes(fullCipherBytes)
            dir.resolve("cipher.db").writeBytes(fullCipherBytes)

            val fullAudit = CryptoAuditVerifier.inspectDatabaseEncryptionOnDisk(dir)
            assertEquals(1, fullAudit.fileCount)
            assertTrue(fullAudit.isEncrypted)
            assertEquals("SQLCIPHER_FULL", fullAudit.encryptionMode)
            assertFalse(fullAudit.hasSaltFile)
            assertTrue(fullAudit.totalBytes >= 4096L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun formatAuditReportMarkdown_rendersTableAndEip191ProofBlock() {
        val report = CryptoAuditReport(
            timestampMs = 1_764_160_000_000L,
            environmentName = "PRODUCTION",
            isDebugBuild = false,
            versionName = "1.0.35",
            versionCode = 32,
            checks = listOf(
                CryptoAuditCheck("k1", "Hardware Keystore KEK", true, 2.5, "level=STRONGBOX"),
                CryptoAuditCheck("k2", "Live XMTP Network", false, 120.0, "timeout")
            ),
            eip191Proof = Eip191AuditProof(
                challenge = "THESIS-AUDIT|0xabc|2026-11-26T13:00:00Z",
                walletAddress = "0xabc",
                recoveredAddress = "0xabc",
                signatureHex = "0x" + "ab".repeat(65),
                isLowS = true,
                verified = true
            )
        )

        val md = CryptoAuditVerifier.formatAuditReportMarkdown(report)
        assertTrue(md.contains("1/2 PASS"))
        assertTrue(md.contains("| Hardware Keystore KEK | PASS | 2.50 | level=STRONGBOX |"))
        assertTrue(md.contains("External EIP-191 Signature Verification Proof"))
        assertTrue(md.contains("Challenge : THESIS-AUDIT|0xabc|2026-11-26T13:00:00Z"))
    }
}
