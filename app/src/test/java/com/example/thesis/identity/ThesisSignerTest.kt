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

/**
 * Cryptographic QA test suite for [ThesisSigner] verifying EIP-191 personal_sign standard,
 * BIP-62 low-S non-malleability, Keccak-256 address derivation, and memory scrubbing.
 */
class ThesisSignerTest {

    // Known test vector credentials:
    // Private Key: 0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318
    // Corresponding Ethereum Address: 0x2c7536e3605d9c16a7a3d7b1898e529396a65c23
    private val testPrivateKeyHex = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"
    private val expectedAddress = "0x2c7536e3605d9c16a7a3d7b1898e529396a65c23"
    private val expectedChecksumAddress = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"

    // Predetermined EIP-191 test vector for message "XMTP MLS Auth"
    private val knownTestMessage = "XMTP MLS Auth"
    private val expectedRHex = "07d78e959b1a26e0cbde8e5f0167d478adea33c164cc797c7e62f3dbea47932f"
    private val expectedSHex = "20fb704f80652f5f07cc9d3d0d6b6aac95bf44623fb9bee4486f30896793f47b"
    private val expectedVByte: Byte = 27 // 0x1b
    private val expectedSignatureHex =
        "0x07d78e959b1a26e0cbde8e5f0167d478adea33c164cc797c7e62f3dbea47932f20fb704f80652f5f07cc9d3d0d6b6aac95bf44623fb9bee4486f30896793f47b1b"

    /**
     * Requirement 1:
     * Signs a predetermined test string ("XMTP MLS Auth") using a known private key,
     * and asserts the produced 65-byte signature matches known r, s, and v components.
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
        assertEquals(
            "Recovered address from known vector signature must match expected address",
            expectedAddress,
            recoveredAddress
        )
    }

    /**
     * Requirement 2:
     * Generates multiple signatures and asserts that the s component never exceeds
     * the curve's half-order (s <= n/2) per BIP-62 and EIP-2.
     */
    @Test
    fun test_signatureNormalizedToLowS() = runTest {
        val curveHalfN = Sign.CURVE_PARAMS.n.shiftRight(1)

        repeat(25) { index ->
            val randomSigner = ThesisSigner.createEphemeral()
            val payload = "Low-S Enforcement Vector #$index: BIP-62 Verification".toByteArray(StandardCharsets.UTF_8)
            val signature = randomSigner.sign(payload)

            assertEquals("Signature length must be 65 bytes", 65, signature.size)

            val sBytes = signature.copyOfRange(32, 64)
            val sScalar = BigInteger(1, sBytes)

            assertTrue("Signature s scalar must be positive", sScalar > BigInteger.ZERO)
            assertTrue(
                "Signature s scalar must satisfy canonical low-S (s <= n/2), but exceeded half-order",
                sScalar <= curveHalfN
            )

            val vByte = signature[64]
            assertTrue(
                "Recovery ID v must be normalized to canonical 27 or 28, got: $vByte",
                vByte == 27.toByte() || vByte == 28.toByte()
            )
        }
    }

    /**
     * Requirement 3:
     * Verifies that the derived Ethereum address matches the last 20 bytes of
     * the Keccak-256 hash of the uncompressed public key coordinates (excluding 0x04 header).
     */
    @Test
    fun test_derivedAddressMatchesPublicKey() {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)

        val uncompressedKey = signer.uncompressedPublicKey
        assertEquals("Uncompressed public key must be exactly 65 bytes", 65, uncompressedKey.size)
        assertEquals("Uncompressed public key must start with 0x04 header byte", 0x04.toByte(), uncompressedKey[0])

        // Strip 0x04 header to isolate raw 64-byte (X, Y) coordinate buffer
        val rawCoordinates64 = uncompressedKey.copyOfRange(1, 65)
        assertEquals(64, rawCoordinates64.size)

        // Compute Keccak-256 digest of the raw curve coordinates
        val keyDigest = Hash.sha3(rawCoordinates64)
        assertEquals(32, keyDigest.size)

        // Address corresponds to the last 20 bytes of the digest
        val addressBytes = keyDigest.copyOfRange(12, 32)
        val calculatedAddress = "0x" + Numeric.toHexStringNoPrefix(addressBytes).lowercase(Locale.ROOT)

        assertEquals(
            "Derived Ethereum address must match last 20 bytes of public key Keccak-256 digest",
            calculatedAddress,
            signer.address.lowercase(Locale.ROOT)
        )
        assertEquals(expectedAddress, signer.address)
        assertEquals(expectedChecksumAddress, signer.checksumAddress)
    }

    /**
     * Requirement 4:
     * Ensures closing an ephemeral signer zeroes internal private key buffers and rejects signing.
     */
    @Test
    fun test_ephemeralKeyWipe() = runTest {
        val signer = ThesisSigner.createEphemeral()
        val payload = "Forensic Memory Verification Payload".toByteArray(StandardCharsets.UTF_8)

        // Signature before close succeeds
        val initialSignature = signer.sign(payload)
        assertEquals(65, initialSignature.size)

        // Inspect internal privateKeyBytes buffer via reflection before closing
        val privateKeyField = ThesisSigner::class.java.getDeclaredField("privateKeyBytes").apply {
            isAccessible = true
        }
        val keyBufferBeforeClose = privateKeyField.get(signer) as ByteArray?
        assertNotNull("Private key buffer must be allocated prior to closing", keyBufferBeforeClose)
        assertFalse(
            "Private key buffer must contain non-zero key material before close",
            keyBufferBeforeClose!!.all { it == 0.toByte() }
        )

        // Execute cryptographic close
        signer.close()
        assertTrue("Signer must report closed", signer.closed)

        // Verify private key buffer was zeroed in place on heap memory
        val keyBufferAfterClose = privateKeyField.get(signer) as ByteArray?
        assertNotNull("Buffer reference remains for inspection", keyBufferAfterClose)
        assertTrue(
            "Every byte of internal privateKeyBytes must be zeroed to 0x00 upon close",
            keyBufferAfterClose!!.all { it == 0.toByte() }
        )

        // Subsequent signing attempts must fail closed with IllegalStateException
        assertThrows(IllegalStateException::class.java) {
            runBlocking { signer.sign(payload) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { signer.sign("XMTP_AUTH_CHALLENGE") }
        }

        // Idempotent close
        signer.close()
        assertTrue("Signer remains closed", signer.closed)
    }

    /**
     * Dual-Mode Operation: External signing delegate (Reown / WalletConnect).
     */
    @Test
    fun test_dualMode_externalSignerDelegate() = runTest {
        val localSigner = ThesisSigner.createEphemeral()
        var delegateCalled = false

        val externalSigner = ThesisSigner(
            address = localSigner.address,
            externalSigner = { msg ->
                delegateCalled = true
                localSigner.sign(msg)
            },
            uncompressedPublicKey = localSigner.uncompressedPublicKey
        )

        assertEquals(localSigner.address, externalSigner.address)

        val challenge = "WalletConnect Delegate Handshake".toByteArray(StandardCharsets.UTF_8)
        val sig = externalSigner.sign(challenge)

        assertTrue("External delegate callback must be invoked", delegateCalled)
        assertEquals(65, sig.size)
        assertArrayEquals(localSigner.sign(challenge), sig)

        val signedData = externalSigner.sign("MLS_INBOX_AUTH")
        assertNotNull(signedData)
        assertEquals(65, signedData.rawData.size)
        assertArrayEquals(localSigner.uncompressedPublicKey, signedData.publicKey)
    }

    /**
     * Dual-Mode Operation: External delegate returning high-S signature is normalized to low-S.
     */
    @Test
    fun test_dualMode_externalSignerNormalizesMalleableSignature() = runTest {
        val localSigner = ThesisSigner.createEphemeral()
        val curveN = Sign.CURVE_PARAMS.n
        val halfN = curveN.shiftRight(1)

        val externalSigner = ThesisSigner(
            address = localSigner.address,
            externalSigner = { msg ->
                val validSig = localSigner.sign(msg)
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

        val msg = "Test Malleability Inversion".toByteArray(StandardCharsets.UTF_8)
        val normalizedSig = externalSigner.sign(msg)

        val sScalar = BigInteger(1, normalizedSig.copyOfRange(32, 64))
        assertTrue("Output signature must be normalized to low-s <= N/2", sScalar <= halfN)
    }

    /**
     * User rejection handling: asserts UserRejectedSignatureException is thrown without crashing.
     */
    @Test
    fun test_signRejectionHandledGracefully() = runTest {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        signer.userApprovalHandler = { false }

        val syncException = assertThrows(UserRejectedSignatureException::class.java) {
            runBlocking { signer.sign(knownTestMessage.toByteArray(StandardCharsets.UTF_8)) }
        }
        assertNotNull(syncException)
        assertTrue(syncException.message?.contains("rejected") == true)

        val asyncException = assertThrows(UserRejectedSignatureException::class.java) {
            runBlocking { signer.sign("XMTP_MLS_AUTH_CHALLENGE") }
        }
        assertNotNull(asyncException)
        assertTrue(asyncException.message?.contains("rejected") == true)

        signer.userApprovalHandler = { true }
        val successfulSig = signer.sign(knownTestMessage.toByteArray(StandardCharsets.UTF_8))
        assertEquals(65, successfulSig.size)
    }

    /**
     * CharArray hex input zeroization test.
     */
    @Test
    fun test_fromHex_charArray_zeroizesInputBuffer() {
        val chars = "0x$testPrivateKeyHex".toCharArray()
        val signer = ThesisSigner.fromHex(chars)

        assertTrue("Input CharArray must be zeroized to \\u0000 post-construction", chars.all { it == '\u0000' })
        assertEquals(expectedAddress, signer.address)
    }

    /**
     * ToString zero-PII test.
     */
    @Test
    fun test_toString_masksSensitiveKeyData() {
        val signer = ThesisSigner.fromHex(testPrivateKeyHex)
        val str = signer.toString()

        assertTrue(str.contains(expectedAddress))
        assertFalse(str.contains(testPrivateKeyHex))
        assertFalse(str.contains("privateKey"))
    }

    /**
     * Invalid private key input boundary validation.
     */
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

        // Scalar >= N (invalid curve order)
        val outOfBounds = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364142", 16)
        val outOfBoundsBytes = Numeric.toBytesPadded(outOfBounds, 32)
        assertThrows(IllegalArgumentException::class.java) {
            ThesisSigner(outOfBoundsBytes)
        }
    }

    /**
     * Multi-threaded concurrent signing thread-safety.
     */
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
        assertTrue("All concurrent tasks should complete within timeout", executor.awaitTermination(5, TimeUnit.SECONDS))
        futures.forEach { it.get() }
    }
}
