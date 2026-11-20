package com.example.thesis.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying the mathematical compliance rules and immutability
 * of [ShredAuditProof] and [ShredResult] under GDPR Article 17 invariants.
 */
class ShredAuditProofTest {

    @Test
    fun test_auditProof_isCompliant_whenAllFundamentalGuaranteesHold() {
        val proof = ShredAuditProof(
            isKeystoreEntryPurged = true,
            isPrimaryDatabaseUnlinked = true,
            isWalJournalUnlinked = true,
            isAccountDirectoryExpunged = true,
            isDekUnwrapPrevented = true,
            deletedArtifactsCount = 4
        )
        assertTrue(proof.isCompliant)
        assertEquals(4, proof.deletedArtifactsCount)
    }

    @Test
    fun test_auditProof_failsCompliance_whenKeystoreEntryNotPurged() {
        val proof = ShredAuditProof(
            isKeystoreEntryPurged = false,
            isPrimaryDatabaseUnlinked = true,
            isWalJournalUnlinked = true,
            isAccountDirectoryExpunged = true,
            isDekUnwrapPrevented = true,
            deletedArtifactsCount = 3
        )
        assertFalse(proof.isCompliant)
    }

    @Test
    fun test_auditProof_failsCompliance_whenDatabaseNotUnlinked() {
        val proof = ShredAuditProof(
            isKeystoreEntryPurged = true,
            isPrimaryDatabaseUnlinked = false,
            isWalJournalUnlinked = true,
            isAccountDirectoryExpunged = true,
            isDekUnwrapPrevented = true,
            deletedArtifactsCount = 0
        )
        assertFalse(proof.isCompliant)
    }

    @Test
    fun test_auditProof_failsCompliance_whenDekUnwrapNotPrevented() {
        val proof = ShredAuditProof(
            isKeystoreEntryPurged = true,
            isPrimaryDatabaseUnlinked = true,
            isWalJournalUnlinked = true,
            isAccountDirectoryExpunged = true,
            isDekUnwrapPrevented = false,
            deletedArtifactsCount = 2
        )
        assertFalse(proof.isCompliant)
    }

    @Test
    fun test_shredResultSuccess_encapsulatesAuditProof() {
        val proof = ShredAuditProof(
            isKeystoreEntryPurged = true,
            isPrimaryDatabaseUnlinked = true,
            isWalJournalUnlinked = true,
            isAccountDirectoryExpunged = true,
            isDekUnwrapPrevented = true,
            deletedArtifactsCount = 5
        )
        val result = ShredResult.Success(deletedFilesCount = 5, auditProof = proof)

        assertEquals(5, result.deletedFilesCount)
        assertNotNull(result.auditProof)
        assertTrue(result.auditProof!!.isCompliant)
    }

    @Test
    fun test_shredResultPartialSuccess_encapsulatesAuditProof() {
        val proof = ShredAuditProof(
            isKeystoreEntryPurged = true,
            isPrimaryDatabaseUnlinked = false,
            isWalJournalUnlinked = false,
            isAccountDirectoryExpunged = false,
            isDekUnwrapPrevented = true,
            deletedArtifactsCount = 1
        )
        val result = ShredResult.PartialSuccess(
            message = "Hardware key destroyed, but storage locked",
            nonFatalErrors = listOf("File locked"),
            auditProof = proof
        )

        assertNotNull(result.auditProof)
        assertFalse(result.auditProof!!.isCompliant)
        assertEquals(1, result.nonFatalErrors.size)
    }

    @Test
    fun test_auditProof_certificateSha256_isDeterministic64Hex() {
        val proofA = ShredAuditProof(
            isKeystoreEntryPurged = true,
            isPrimaryDatabaseUnlinked = true,
            isWalJournalUnlinked = true,
            isAccountDirectoryExpunged = true,
            isDekUnwrapPrevented = true,
            deletedArtifactsCount = 5,
            timestampEpochMs = 1_795_169_500_000L
        )
        val proofB = proofA.copy()
        val proofC = proofA.copy(deletedArtifactsCount = 4)

        val hexPattern = Regex("^[0-9a-f]{64}$")
        assertTrue(hexPattern.matches(proofA.certificateSha256))
        assertEquals(proofA.certificateSha256, proofB.certificateSha256)
        assertTrue(proofA.certificateSha256 != proofC.certificateSha256)
        assertTrue(proofA.toCertificateSummary().contains(proofA.certificateSha256))
    }
}
