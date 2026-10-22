package com.example.thesis.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import org.xmtp.android.library.libxmtp.IdentityKind
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ThesisSignerTest {

    // Standard deterministic test vector:
    // Private Key: 0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318
    // Corresponding Ethereum Address: 0x2c7536e3605d9c16a7a3d7b1898e529396a65c23
    private val testPrivateKeyHex = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"
    private val expectedAddress = "0x2c7536e3605d9c16a7a3d7b1898e529396a65c23"
    private val expectedChecksumAddress = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"

    // Predetermined EIP-191 test vector for message "Hello XMTP"
    private val knownTestMessage = "Hello XMTP"
    private val expectedSignatureHex =
        "0x98a23cc1f6cef7d7a5afb663cd32275363a16547ba62b875573f489e98a8d9f349d33ca4cbc02c4ff41114fdffcaa3b8bb32f3fb9aec308b3d75ffb828dd29d21b"
    private val expectedRHex = "98a23cc1f6cef7d7a5afb663cd32275363a16547ba62b875573f489e98a8d9f3"
    private val expectedSHex = "49d33ca4cbc02c4ff41114fdffcaa3b8bb32f3fb9aec308b3d75ffb828dd29d2"
    private val expectedVByte: Byte = 27 // 0x1b

    /**
     * Requirement 1:
     * Signs a predetermined string ("Hello XMTP") using a known hardcoded test private key,
     * and asserts the generated (r, s, v) signature matches the known expected hex string.
     */
    @Test
    fun test_eip191_signatureVerificationAgainstKnownVector() {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        val messageBytes = knownTestMessage.toByteArray(StandardCharsets.UTF_8)

        val signature = signer.sign(messageBytes)

        assertEquals("Signature must be exactly 65 bytes", 65, signature.size)
        val actualSignatureHex = Numeric.toHexString(signature)
        assertEquals("Full 65-byte RSV hex must match known vector", expectedSignatureHex, actualSignatureHex)

        val r = signature.copyOfRange(0, 32)
        val s = signature.copyOfRange(32, 64)
        val v = signature[64]

        assertEquals("r component must match expected vector", expectedRHex, Numeric.toHexStringNoPrefix(r))
        assertEquals("s component must match expected vector", expectedSHex, Numeric.toHexStringNoPrefix(s))
        assertEquals("v component must match expected canonical 27 (0x1b)", expectedVByte, v)

        // Mathematical public key recovery verification
        val sigData = Sign.SignatureData(byteArrayOf(v), r, s)
        val recoveredKey = Sign.signedPrefixedMessageToKey(messageBytes, sigData)
        val recoveredAddress = "0x" + Keys.getAddress(recoveredKey).lowercase(Locale.ROOT)
        assertEquals("Recovered address from known vector signature must match expected address", expectedAddress, recoveredAddress)
    }

    /**
     * Requirement 2:
     * Verifies that `signer.address` corresponds strictly to the uncompressed public key's
     * Keccak-256 hash (last 20 bytes).
     */
    @Test
    fun test_signerAddressMatchesDerivedPublicKey() {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        val uncompressedKey = signer.uncompressedPublicKey

        assertEquals("Uncompressed key must have 65 bytes (0x04 header + 64 bytes)", 65, uncompressedKey.size)
        assertEquals("Uncompressed header byte must be 0x04", 0x04.toByte(), uncompressedKey[0])

        // Strip the 0x04 uncompressed prefix byte: take the 64-byte (X || Y) point
        val publicKeyPointBytes = uncompressedKey.copyOfRange(1, 65)
        assertEquals(64, publicKeyPointBytes.size)

        // Compute Keccak-256 hash of the 64-byte public point
        val pointHash = Hash.sha3(publicKeyPointBytes)
        assertEquals(32, pointHash.size)

        // Address is the last 20 bytes of the Keccak-256 digest
        val last20Bytes = pointHash.copyOfRange(12, 32)
        assertEquals(20, last20Bytes.size)

        val derivedAddress = "0x" + Numeric.toHexStringNoPrefix(last20Bytes).lowercase(Locale.ROOT)
        assertEquals("Signer address must match last 20 bytes of Keccak-256 hash of uncompressed key", derivedAddress, signer.address)
    }

    /**
     * Requirement 3:
     * Verifies that the `s` component of the generated signature is in the lower half
     * of the secp256k1 group order (s <= n / 2) to eliminate BIP-62 / EIP-2 signature malleability.
     */
    @Test
    fun test_signatureNormalizedToLowS() {
        val halfCurveOrder = Sign.CURVE_PARAMS.n.shiftRight(1)

        // Test with the deterministic known vector
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        val sig1 = signer.sign(knownTestMessage.toByteArray(StandardCharsets.UTF_8))
        val s1 = BigInteger(1, sig1.copyOfRange(32, 64))
        assertTrue("s must be positive", s1 > BigInteger.ZERO)
        assertTrue("s must be in lower half of secp256k1 curve order (s <= n / 2)", s1 <= halfCurveOrder)

        // Test across 20 distinct random signers and varied payloads
        repeat(20) { index ->
            val randomSigner = ThesisSigner.generateRandom()
            val payload = "Challenge Payload #$index: Non-malleability verification".toByteArray(StandardCharsets.UTF_8)
            val signature = randomSigner.sign(payload)

            val sBytes = signature.copyOfRange(32, 64)
            val sScalar = BigInteger(1, sBytes)

            assertTrue("Random signature s must be positive", sScalar > BigInteger.ZERO)
            assertTrue("Random signature s must be <= n / 2 (EIP-2 low-s)", sScalar <= halfCurveOrder)

            val vByte = signature[64]
            assertTrue("v must be canonical 27 or 28", vByte == 27.toByte() || vByte == 28.toByte())
        }
    }

    /**
     * Requirement 4:
     * Simulates a canceled user sign request; asserts it maps cleanly to a typed
     * domain exception without crashing the runtime.
     */
    @Test
    fun test_signRejectionHandledGracefully() {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)

        // Simulate user clicking "Cancel" / "Reject" on wallet prompt
        signer.userApprovalHandler = { false }

        // Assert direct synchronous call throws typed UserRejectedSignatureException
        val syncException = assertThrows(UserRejectedSignatureException::class.java) {
            signer.sign(knownTestMessage.toByteArray(StandardCharsets.UTF_8))
        }
        assertNotNull("Exception must not be null", syncException)
        assertTrue("Exception message should indicate rejection", syncException.message?.contains("rejected") == true)

        // Assert suspend XMTP interface call also throws typed UserRejectedSignatureException
        val asyncException = assertThrows(UserRejectedSignatureException::class.java) {
            runBlocking {
                signer.sign("XMTP_MLS_INBOX_AUTH_CHALLENGE")
            }
        }
        assertNotNull("Async exception must not be null", asyncException)
        assertTrue("Async message should indicate rejection", asyncException.message?.contains("rejected") == true)

        // Simulate application error boundary handling (e.g. mapping to typed error state)
        val mappedErrorResult: String = try {
            signer.sign("Challenge".toByteArray(StandardCharsets.UTF_8))
            "SUCCESS"
        } catch (e: UserRejectedSignatureException) {
            // Graceful domain recovery without process crash
            "SIGNATURE_REJECTED_BY_USER"
        } catch (e: Exception) {
            "UNEXPECTED_FAILURE"
        }

        assertEquals("SIGNATURE_REJECTED_BY_USER", mappedErrorResult)

        // Reset approval handler: subsequent signing succeeds normally
        signer.userApprovalHandler = { true }
        val successfulSig = signer.sign(knownTestMessage.toByteArray(StandardCharsets.UTF_8))
        assertEquals(65, successfulSig.size)
    }

    @Test
    fun test_identityWrapping_addressDerivationAndPublicIdentity() {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)

        assertEquals("Lowercase address must match expected", expectedAddress, signer.address)
        assertEquals("Checksummed address must match EIP-55 format", expectedChecksumAddress, signer.checksumAddress)
        assertEquals("IdentityKind must be ETHEREUM", IdentityKind.ETHEREUM, signer.publicIdentity.kind)
        assertEquals("Public identity identifier must be the address", expectedAddress, signer.publicIdentity.identifier)
        assertEquals("Uncompressed public key must be 65 bytes", 65, signer.uncompressedPublicKey.size)
        assertEquals("Uncompressed public key prefix must be 0x04", 0x04.toByte(), signer.uncompressedPublicKey[0])
    }

    @Test
    fun test_xmtp_signingKey_signedDataMatches() = runBlocking {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        val challenge = "XMTP_INBOX_REGISTRATION_CHALLENGE_2026"

        val signedData = signer.sign(challenge)

        assertNotNull("SignedData must not be null", signedData)
        assertEquals("Raw data must be 65 bytes", 65, signedData.rawData.size)
        assertNotNull("Public key must be populated", signedData.publicKey)
        assertEquals("Public key must be 65 bytes", 65, signedData.publicKey!!.size)
        assertArrayEquals("Public key in SignedData must match uncompressed key", signer.uncompressedPublicKey, signedData.publicKey)

        val r = signedData.rawData.copyOfRange(0, 32)
        val s = signedData.rawData.copyOfRange(32, 64)
        val v = signedData.rawData[64]
        val sigData = Sign.SignatureData(byteArrayOf(v), r, s)
        val recoveredKey = Sign.signedPrefixedMessageToKey(challenge.toByteArray(StandardCharsets.UTF_8), sigData)
        val recoveredAddress = "0x" + Keys.getAddress(recoveredKey).lowercase(Locale.ROOT)
        assertEquals("Recovered address must match signer address", expectedAddress, recoveredAddress)
    }

    @Test
    fun test_autoCloseable_scrubsMemoryAndRejectsSubsequentSigning() {
        val keyBytes = Numeric.hexStringToByteArray(testPrivateKeyHex)
        val signer = ThesisSigner(keyBytes)

        val message = "Testing Memory Hygiene".toByteArray(StandardCharsets.UTF_8)
        val initialSignature = signer.sign(message)
        assertEquals(65, initialSignature.size)

        signer.close()
        assertTrue("Signer must report closed", signer.closed)

        assertThrows(IllegalStateException::class.java) {
            signer.sign(message)
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                signer.sign("Challenge".toByteArray(StandardCharsets.UTF_8))
            }
        }

        signer.close()
        assertTrue("Signer remains closed", signer.closed)
    }

    @Test
    fun test_fromHex_charArray_zeroizesInputBuffer() {
        val chars = "0x$testPrivateKeyHex".toCharArray()
        val signer = ThesisSigner.fromHex(chars)

        assertTrue("Hex characters must be scrubbed after initialization", chars.all { it == '\u0000' })
        assertEquals(expectedAddress, signer.address)
    }

    @Test
    fun test_toString_masksSensitiveKeyData() {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        val str = signer.toString()

        assertTrue(str.contains(expectedAddress))
        assertFalse(str.contains(testPrivateKeyHex))
        assertFalse(str.contains("privateKey"))
    }

    @Test
    fun test_invalidPrivateKeyInput_throwsIllegalArgumentException() {
        // Size != 32
        assertThrows(IllegalArgumentException::class.java) {
            ThesisSigner(ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThesisSigner(ByteArray(33))
        }

        // Scalar = 0
        assertThrows(IllegalArgumentException::class.java) {
            ThesisSigner(ByteArray(32)) // all zeros
        }

        // Scalar >= N (invalid secp256k1 scalar)
        val outOfBounds = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364142", 16)
        val outOfBoundsBytes = Numeric.toBytesPadded(outOfBounds, 32)
        assertThrows(IllegalArgumentException::class.java) {
            ThesisSigner(outOfBoundsBytes)
        }
    }

    @Test
    fun test_concurrency_threadSafeSigning() {
        val signer = ThesisSigner.generateRandom()
        val executor = Executors.newFixedThreadPool(8)
        val iterations = 50
        val payload = "Concurrent EIP-191 Signing Validation".toByteArray(StandardCharsets.UTF_8)

        val expectedSig = signer.sign(payload)

        val futures = (0 until iterations).map {
            executor.submit {
                val sig = signer.sign(payload)
                assertArrayEquals("Every concurrent signature must produce deterministic output", expectedSig, sig)
            }
        }

        executor.shutdown()
        assertTrue("All tasks should complete", executor.awaitTermination(5, TimeUnit.SECONDS))
        futures.forEach { it.get() }
    }
}
