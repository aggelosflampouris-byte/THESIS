package com.example.thesis.security

import com.example.thesis.identity.ThesisSigner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

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
    fun inspectDatabaseEncryptionOnDisk_distinguishesPlaintextSqliteFromCiphertext() {
        val dir = Files.createTempDirectory("cipher-audit-test").toFile()
        try {
            val plainDb = dir.resolve("plain.db")
            plainDb.writeBytes("SQLite format 3\u0000remainder-bytes".toByteArray(StandardCharsets.US_ASCII))
            val plainAudit = CryptoAuditVerifier.inspectDatabaseEncryptionOnDisk(dir)
            assertEquals(1, plainAudit.fileCount)
            assertTrue(plainAudit.hasPlaintextSqliteHeader)

            plainDb.delete()
            val cipherDb = dir.resolve("cipher.db")
            cipherDb.writeBytes(ByteArray(64) { (it * 7 + 3).toByte() })
            val cipherAudit = CryptoAuditVerifier.inspectDatabaseEncryptionOnDisk(dir)
            assertEquals(1, cipherAudit.fileCount)
            assertFalse(cipherAudit.hasPlaintextSqliteHeader)
            assertTrue(cipherAudit.totalBytes >= 64L)
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

