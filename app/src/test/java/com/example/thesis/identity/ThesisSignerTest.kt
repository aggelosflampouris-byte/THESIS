package com.example.thesis.identity

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
    fun test_eip191_signatureVerificationAgainstKnownVector() = runTest {
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
     * Requirement 1: Address derivation accuracy.
     */
    @Test
    fun test_signerAddressMatchesDerivedPublicKey() {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        assertEquals("Signer address must match known Ethereum public address", expectedAddress, signer.address)
        assertEquals("Checksummed address must follow EIP-55 casing", expectedChecksumAddress, signer.checksumAddress)
    }

    /**
     * Requirement 2:
     * Asserts that recovery ID byte v is strictly canonical {27, 28} and never {0, 1} or EIP-155 chain IDs.
     */
    @Test
    fun test_recoveryId_v_normalization() = runTest {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        val testPayloads = listOf(
            "Test Payload Alpha",
            "Random string for v normalization verification",
            "XMTP v4 MLS Handshake Vector",
            "Arbitrary binary challenge string \u0000\u0001\u0002"
        )

        for (text in testPayloads) {
            val signature = signer.sign(text.toByteArray(StandardCharsets.UTF_8))
            val v = signature[64]

            assertTrue(
                "v recovery byte must be canonical 27 (0x1b) or 28 (0x1c), but got: $v",
                v == 27.toByte() || v == 28.toByte()
            )
            assertFalse("v must not be legacy unshifted parity byte 0", v == 0.toByte())
            assertFalse("v must not be legacy unshifted parity byte 1", v == 1.toByte())
        }
    }

    /**
     * Requirement 2 & BIP-62 Non-Malleability:
     * Verifies that s <= n / 2 across 20 randomized key generations and distinct payloads.
     */
    @Test
    fun test_bip62_lowS_enforcement() = runTest {
        val halfCurveOrder = Sign.CURVE_PARAMS.n.shiftRight(1)

        repeat(20) { index ->
            val randomSigner = ThesisSigner.createEphemeral()
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
     * Requirement 3: Dual-Mode Operation - Ephemeral in-memory wallet factory.
     */
    @Test
    fun test_createEphemeral_factoryMethod() = runTest {
        val ephemeral1 = ThesisSigner.createEphemeral()
        val ephemeral2 = ThesisSigner.createEphemeral()

        assertNotNull(ephemeral1.address)
        assertNotNull(ephemeral2.address)
        assertTrue(ephemeral1.address.startsWith("0x"))
        assertEquals(42, ephemeral1.address.length)
        assertFalse("Two ephemeral instances must have unique addresses", ephemeral1.address == ephemeral2.address)

        val challenge = "Ephemeral sign challenge".toByteArray(StandardCharsets.UTF_8)
        val sig = ephemeral1.sign(challenge)
        assertEquals(65, sig.size)
    }

    /**
     * Requirement 3: Dual-Mode Operation - External signing callback (Reown / WalletConnect delegate).
     */
    @Test
    fun test_dualMode_externalSignerDelegate() = runTest {
        val localKeySigner = ThesisSigner.createEphemeral()
        var delegateInvoked = false

        // Instantiate ThesisSigner in external delegate mode
        val externalSigner = ThesisSigner(
            address = localKeySigner.address,
            externalSigner = { message ->
                delegateInvoked = true
                localKeySigner.sign(message)
            },
            uncompressedPublicKey = localKeySigner.uncompressedPublicKey
        )

        assertEquals(localKeySigner.address, externalSigner.address)
        assertEquals(localKeySigner.checksumAddress, externalSigner.checksumAddress)

        val message = "External delegate challenge".toByteArray(StandardCharsets.UTF_8)
        val signature = externalSigner.sign(message)

        assertTrue("External delegate callback must be invoked", delegateInvoked)
        assertEquals(65, signature.size)
        assertArrayEquals(localKeySigner.sign(message), signature)

        // Verify XMTP interface method works through delegate
        val signedData = externalSigner.sign("XMTP_CHALLENGE_STRING")
        assertNotNull(signedData)
        assertEquals(65, signedData.rawData.size)
        assertArrayEquals(localKeySigner.uncompressedPublicKey, signedData.publicKey)
    }

    /**
     * Requirement 3: External delegate with unnormalized high-s output is normalized by ThesisSigner.
     */
    @Test
    fun test_dualMode_externalSignerNormalizesMalleableSignature() = runTest {
        val localKeySigner = ThesisSigner.createEphemeral()
        val curveN = Sign.CURVE_PARAMS.n
        val halfN = curveN.shiftRight(1)

        val externalSigner = ThesisSigner(
            address = localKeySigner.address,
            externalSigner = { message ->
                val validSig = localKeySigner.sign(message)
                // Intentionally flip s to high-s: s' = N - s, flip v
                val sBytes = validSig.copyOfRange(32, 64)
                val originalS = BigInteger(1, sBytes)
                val highS = curveN.subtract(originalS)
                val highSBytes = Numeric.toBytesPadded(highS, 32)
                val flippedV = if (validSig[64] == 27.toByte()) 28.toByte() else 27.toByte()

                val malleableSig = ByteArray(65)
                System.arraycopy(validSig, 0, malleableSig, 0, 32)
                System.arraycopy(highSBytes, 0, malleableSig, 32, 32)
                malleableSig[64] = flippedV
                malleableSig
            }
        )

        val message = "Test Malleability Recovery".toByteArray(StandardCharsets.UTF_8)
        val normalizedSig = externalSigner.sign(message)

        val sScalar = BigInteger(1, normalizedSig.copyOfRange(32, 64))
        assertTrue("Output signature must be normalized to low-s <= N/2", sScalar <= halfN)
    }

    /**
     * Simulates a canceled user sign request; asserts it maps cleanly to a typed domain exception.
     */
    @Test
    fun test_signRejectionHandledGracefully() = runTest {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)

        signer.userApprovalHandler = { false }

        val syncException = assertThrows(UserRejectedSignatureException::class.java) {
            runBlocking {
                signer.sign(knownTestMessage.toByteArray(StandardCharsets.UTF_8))
            }
        }
        assertNotNull("Exception must not be null", syncException)
        assertTrue("Exception message should indicate rejection", syncException.message?.contains("rejected") == true)

        val asyncException = assertThrows(UserRejectedSignatureException::class.java) {
            runBlocking {
                signer.sign("XMTP_MLS_INBOX_AUTH_CHALLENGE")
            }
        }
        assertNotNull("Async exception must not be null", asyncException)
        assertTrue("Async message should indicate rejection", asyncException.message?.contains("rejected") == true)

        // Reset approval handler
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
    fun test_xmtp_signingKey_signedDataMatches() = runTest {
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
    fun test_autoCloseable_scrubsMemoryAndRejectsSubsequentSigning() = runTest {
        val keyBytes = Numeric.hexStringToByteArray(testPrivateKeyHex)
        val signer = ThesisSigner(keyBytes)

        val message = "Testing Memory Hygiene".toByteArray(StandardCharsets.UTF_8)
        val initialSignature = signer.sign(message)
        assertEquals(65, initialSignature.size)

        signer.close()
        assertTrue("Signer must report closed", signer.closed)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { signer.sign(message) }
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { signer.sign("Challenge") }
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
            ThesisSigner(ByteArray(32))
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
        val signer = ThesisSigner.createEphemeral()
        val executor = Executors.newFixedThreadPool(8)
        val iterations = 50
        val payload = "Concurrent EIP-191 Signing Validation".toByteArray(StandardCharsets.UTF_8)

        val expectedSig = signer.signBlocking(payload)

        val futures = (0 until iterations).map {
            executor.submit {
                val sig = signer.signBlocking(payload)
                assertArrayEquals("Every concurrent signature must produce deterministic output", expectedSig, sig)
            }
        }

        executor.shutdown()
        assertTrue("All tasks should complete", executor.awaitTermination(5, TimeUnit.SECONDS))
        futures.forEach { it.get() }
    }
}
