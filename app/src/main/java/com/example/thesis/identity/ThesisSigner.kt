package com.example.thesis.identity

import com.example.thesis.util.CryptoUtils
import kotlinx.coroutines.runBlocking
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import org.xmtp.android.library.SignedData
import org.xmtp.android.library.SignerType
import org.xmtp.android.library.SigningKey
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.PublicIdentity
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.SignatureException
import java.util.Arrays
import java.util.Locale

/**
 * Typed domain exception emitted when a user or wallet explicitly cancels or rejects
 * an EIP-191 signature challenge.
 */
class UserRejectedSignatureException(
    message: String = "User rejected the signature request",
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Typealias representing an external asynchronous signing delegate (e.g., Reown / AppKit / WalletConnect).
 */
typealias ExternalSignerCallback = suspend (message: ByteArray) -> ByteArray

/**
 * Production-grade Web3 Ethereum identity signer implementing XMTP's [SigningKey]
 * and [AutoCloseable] with defense-in-depth cryptographic controls.
 *
 * ## Architectural & Cryptographic Invariants
 * 1. **EIP-191 Personal Sign Standard**: Prepends `"\x19Ethereum Signed Message:\n" + message.length + message`
 *    before Keccak-256 hashing to eliminate cross-protocol collision and transaction replay vulnerabilities.
 * 2. **BIP-62 / EIP-2 Low-S Non-Malleability**: Guarantees canonical curve scalar output (`s <= n / 2`).
 *    Automatically inverts malleable high-s components (`s' = n - s`) and flips recovery ID parity (`v`).
 * 3. **Dual-Mode Operation**:
 *    - **Local In-Memory Mode**: Retains private key bytes securely in memory with zeroization upon [close].
 *    - **External Delegate Mode**: Delegates `personal_sign` challenges to remote wallets (Reown / WalletConnect)
 *      without local private key custody, while enforcing canonical low-S normalization on returned signatures.
 * 4. **Defensive Memory Lifecycle**: Immediate in-place scrubbing of intermediate buffers, ephemeral private
 *    keys on [close], and [CharArray] inputs to prevent heap/string pool key retention.
 */
class ThesisSigner : SigningKey, AutoCloseable {

    private val lock = Any()

    @Volatile
    private var isClosed: Boolean = false

    private val privateKeyBytes: ByteArray?
    private var activeKeyPair: ECKeyPair?

    private val externalSigner: ExternalSignerCallback?

    /**
     * Optional authorization hook to simulate or intercept interactive user consent.
     * When returning false, [sign] aborts and emits [UserRejectedSignatureException].
     */
    var userApprovalHandler: (() -> Boolean)? = null

    /** Canonical lowercase 0x-prefixed 42-character Ethereum address. */
    val address: String

    /** EIP-55 mixed-case checksummed Ethereum address. */
    val checksumAddress: String

    private var uncompressedPublicKeyBytes: ByteArray?

    /** Defensive copy of the 65-byte uncompressed secp256k1 public key (0x04 || X || Y). */
    val uncompressedPublicKey: ByteArray
        get() = checkNotNull(uncompressedPublicKeyBytes) { "Public key is unavailable for uninitialized signer" }.clone()

    override val publicIdentity: PublicIdentity
    override val type: SignerType = SignerType.EOA
    override var chainId: Long? = null
    override var blockNumber: Long? = null

    /**
     * Local private key constructor.
     *
     * @param privateKey Exactly 32-byte secp256k1 scalar in [1, N-1].
     */
    constructor(privateKey: ByteArray) {
        require(privateKey.size == PRIVATE_KEY_BYTE_LENGTH) {
            "Private key must be exactly $PRIVATE_KEY_BYTE_LENGTH bytes, got ${privateKey.size}"
        }

        val scalar = BigInteger(1, privateKey)
        require(scalar > BigInteger.ZERO && scalar < SECP256K1_N) {
            "Private key scalar is outside valid secp256k1 range [1, N - 1]"
        }

        this.privateKeyBytes = privateKey.clone()
        val keyPair = ECKeyPair.create(this.privateKeyBytes)
        this.activeKeyPair = keyPair
        this.externalSigner = null

        val rawAddressHex = Keys.getAddress(keyPair).lowercase(Locale.ROOT)
        this.address = if (rawAddressHex.startsWith(HEX_PREFIX)) rawAddressHex else "$HEX_PREFIX$rawAddressHex"
        this.checksumAddress = Keys.toChecksumAddress(this.address)
        this.publicIdentity = PublicIdentity(IdentityKind.ETHEREUM, this.address)

        val rawPublicKey64 = Numeric.toBytesPadded(keyPair.publicKey, COMPONENT_LENGTH * 2)
        this.uncompressedPublicKeyBytes = ByteArray(UNCOMPRESSED_KEY_LENGTH).apply {
            this[0] = UNCOMPRESSED_HEADER_BYTE
            System.arraycopy(rawPublicKey64, 0, this, 1, rawPublicKey64.size)
        }
    }

    /**
     * Production constructor for external wallet delegation (e.g., Reown / AppKit / WalletConnect).
     *
     * @param address               User's verified Ethereum address.
     * @param externalSigner        External callback executing EIP-191 personal_sign via the connected wallet.
     * @param uncompressedPublicKey Optional 65-byte uncompressed public key (0x04 || X || Y).
     */
    constructor(
        address: String,
        externalSigner: ExternalSignerCallback,
        uncompressedPublicKey: ByteArray? = null
    ) {
        val sanitized = checkNotNull(CryptoUtils.sanitiseEthereumAddress(address)) {
            "Invalid Ethereum address format for external signer: $address"
        }
        this.address = sanitized
        this.checksumAddress = Keys.toChecksumAddress(sanitized)
        this.publicIdentity = PublicIdentity(IdentityKind.ETHEREUM, this.address)

        this.privateKeyBytes = null
        this.activeKeyPair = null
        this.externalSigner = externalSigner

        if (uncompressedPublicKey != null) {
            require(uncompressedPublicKey.size == UNCOMPRESSED_KEY_LENGTH && uncompressedPublicKey[0] == UNCOMPRESSED_HEADER_BYTE) {
                "Uncompressed public key must be 65 bytes beginning with 0x04"
            }
            this.uncompressedPublicKeyBytes = uncompressedPublicKey.clone()
        } else {
            this.uncompressedPublicKeyBytes = null
        }
    }

    /**
     * Asynchronously signs an arbitrary byte payload conforming to EIP-191 personal_sign:
     * `"\x19Ethereum Signed Message:\n" + message.length + message`.
     *
     * Returns a 65-byte compact RSV array (`r` [32] || `s` [32] || `v` [1]) strictly normalized to low-S.
     *
     * @param message Arbitrary byte array payload to be signed.
     * @return 65-byte canonical RSV signature.
     */
    suspend fun sign(message: ByteArray): ByteArray {
        synchronized(lock) {
            check(!isClosed) { "ThesisSigner has been closed and memory material scrubbed" }
        }

        val approved = userApprovalHandler?.invoke() ?: true
        if (!approved) {
            throw UserRejectedSignatureException("User rejected the signature request")
        }

        val activeDelegate = externalSigner
        val rawSigBytes: ByteArray = if (activeDelegate != null) {
            val externalSig = activeDelegate.invoke(message)
            require(externalSig.size == SIGNATURE_RSV_LENGTH) {
                "External wallet returned invalid signature length: ${externalSig.size} (expected 65 bytes)"
            }
            externalSig
        } else {
            val keyPair = synchronized(lock) {
                checkNotNull(activeKeyPair) { "Signer active key pair is uninitialized or destroyed" }
            }

            val lengthAscii = message.size.toString().toByteArray(StandardCharsets.US_ASCII)
            val prefixedPayload = ByteArray(EIP191_HEADER_BYTES.size + lengthAscii.size + message.size)

            try {
                System.arraycopy(EIP191_HEADER_BYTES, 0, prefixedPayload, 0, EIP191_HEADER_BYTES.size)
                System.arraycopy(lengthAscii, 0, prefixedPayload, EIP191_HEADER_BYTES.size, lengthAscii.size)
                System.arraycopy(message, 0, prefixedPayload, EIP191_HEADER_BYTES.size + lengthAscii.size, message.size)

                val messageDigest = Hash.sha3(prefixedPayload)
                val signatureData = Sign.signMessage(messageDigest, keyPair, false)

                val rsv = ByteArray(SIGNATURE_RSV_LENGTH)
                System.arraycopy(signatureData.r, 0, rsv, SIGNATURE_R_OFFSET, COMPONENT_LENGTH)
                System.arraycopy(signatureData.s, 0, rsv, SIGNATURE_S_OFFSET, COMPONENT_LENGTH)
                rsv[SIGNATURE_V_OFFSET] = signatureData.v[0]
                rsv
            } finally {
                Arrays.fill(prefixedPayload, 0x00.toByte())
            }
        }

        val normalizedRsv = normalizeSignature(rawSigBytes)

        if (uncompressedPublicKeyBytes == null) {
            synchronized(lock) {
                if (uncompressedPublicKeyBytes == null) {
                    uncompressedPublicKeyBytes = recoverPublicKey(message, normalizedRsv)
                }
            }
        }

        return normalizedRsv
    }

    /**
     * Implements XMTP's [SigningKey.sign] interface method.
     * Produces a [SignedData] envelope wrapping the 65-byte canonical RSV signature and public key.
     */
    override suspend fun sign(message: String): SignedData {
        val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
        val rawSignature = sign(messageBytes)
        val pubKey = checkNotNull(uncompressedPublicKeyBytes) { "Public key resolution failed" }.clone()

        return SignedData(
            rawData = rawSignature,
            publicKey = pubKey,
            authenticatorData = null,
            clientDataJson = null
        )
    }

    /**
     * Convenience method to sign a string message and return the raw 65-byte RSV byte array.
     */
    suspend fun signMessage(message: String): ByteArray {
        return sign(message.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Synchronous blocking wrapper for callers without a coroutine context.
     */
    fun signBlocking(message: ByteArray): ByteArray = runBlocking {
        sign(message)
    }

    /**
     * Enforces BIP-62 / EIP-2 low-S normalization and canonical recovery byte {27, 28}.
     *
     * Mathematically handles legacy {0, 1}, canonical {27, 28}, and EIP-155 chain-specific v values.
     * Inverts s to n - s when s > n / 2 and flips recovery parity accordingly.
     */
    private fun normalizeSignature(rawSignature: ByteArray): ByteArray {
        val r = rawSignature.copyOfRange(SIGNATURE_R_OFFSET, SIGNATURE_S_OFFSET)
        val s = rawSignature.copyOfRange(SIGNATURE_S_OFFSET, SIGNATURE_V_OFFSET)
        var sScalar = BigInteger(1, s)
        val rawV = rawSignature[SIGNATURE_V_OFFSET].toInt() and 0xFF

        require(sScalar > BigInteger.ZERO && sScalar < SECP256K1_N) {
            "Signature s-scalar is outside valid secp256k1 range [1, N-1]"
        }

        val initialParity = when {
            rawV == 0 || rawV == 1 -> rawV
            rawV == 27 || rawV == 28 -> rawV - RECOVERY_ID_OFFSET
            rawV >= EIP155_V_THRESHOLD -> (rawV - EIP155_V_THRESHOLD) % 2
            else -> throw IllegalStateException("Non-canonical recovery ID v produced: $rawV")
        }

        val isHighS = sScalar > SECP256K1_HALF_N
        val finalParity = if (isHighS) 1 - initialParity else initialParity

        if (isHighS) {
            sScalar = SECP256K1_N.subtract(sScalar)
        }

        check(sScalar > BigInteger.ZERO && sScalar <= SECP256K1_HALF_N) {
            "Signature s-value exceeds N/2: EIP-2 signature malleability violation"
        }

        val canonicalV = (RECOVERY_ID_OFFSET + finalParity).toByte()
        val normalizedS = Numeric.toBytesPadded(sScalar, COMPONENT_LENGTH)
        val normalizedRsv = ByteArray(SIGNATURE_RSV_LENGTH)
        System.arraycopy(r, 0, normalizedRsv, SIGNATURE_R_OFFSET, COMPONENT_LENGTH)
        System.arraycopy(normalizedS, 0, normalizedRsv, SIGNATURE_S_OFFSET, COMPONENT_LENGTH)
        normalizedRsv[SIGNATURE_V_OFFSET] = canonicalV

        return normalizedRsv
    }

    private fun recoverPublicKey(message: ByteArray, rsvSignature: ByteArray): ByteArray {
        val r = rsvSignature.copyOfRange(SIGNATURE_R_OFFSET, SIGNATURE_S_OFFSET)
        val s = rsvSignature.copyOfRange(SIGNATURE_S_OFFSET, SIGNATURE_V_OFFSET)
        val v = rsvSignature[SIGNATURE_V_OFFSET]
        val sigData = Sign.SignatureData(byteArrayOf(v), r, s)

        val recoveredPublicKeyBigInt: BigInteger = try {
            Sign.signedPrefixedMessageToKey(message, sigData)
        } catch (e: SignatureException) {
            throw IllegalArgumentException("Cryptographic public key recovery failed for signature", e)
        }

        val recoveredAddress = "0x" + Keys.getAddress(recoveredPublicKeyBigInt).lowercase(Locale.ROOT)
        check(recoveredAddress == this.address.lowercase(Locale.ROOT)) {
            "Recovered address ($recoveredAddress) does not match expected signer identity (${this.address})"
        }

        val rawPublicKey64 = Numeric.toBytesPadded(recoveredPublicKeyBigInt, COMPONENT_LENGTH * 2)

        return ByteArray(UNCOMPRESSED_KEY_LENGTH).apply {
            this[0] = UNCOMPRESSED_HEADER_BYTE
            System.arraycopy(rawPublicKey64, 0, this, 1, rawPublicKey64.size)
        }
    }

    /**
     * Securely zeroizes internal private key byte buffers in place and releases references.
     * Idempotent and thread-safe.
     */
    override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                if (privateKeyBytes != null) {
                    Arrays.fill(privateKeyBytes, 0x00.toByte())
                }
                activeKeyPair = null
                isClosed = true
            }
        }
    }

    val closed: Boolean
        get() = isClosed

    override fun toString(): String = "ThesisSigner(address=$address, closed=$isClosed)"

    companion object {
        private const val PRIVATE_KEY_BYTE_LENGTH = 32
        private const val SIGNATURE_RSV_LENGTH = 65
        private const val SIGNATURE_R_OFFSET = 0
        private const val SIGNATURE_S_OFFSET = 32
        private const val SIGNATURE_V_OFFSET = 64
        private const val COMPONENT_LENGTH = 32
        private const val UNCOMPRESSED_KEY_LENGTH = 65
        private const val UNCOMPRESSED_HEADER_BYTE: Byte = 0x04
        private const val RECOVERY_ID_OFFSET = 27
        private const val EIP155_V_THRESHOLD = 35
        private const val HEX_PREFIX = "0x"

        private val EIP191_HEADER_BYTES: ByteArray =
            "\u0019Ethereum Signed Message:\n".toByteArray(StandardCharsets.US_ASCII)

        private val SECP256K1_N: BigInteger = Sign.CURVE_PARAMS.n
        private val SECP256K1_HALF_N: BigInteger = SECP256K1_N.shiftRight(1)

        /**
         * Factory method generating an ephemeral in-memory signer with a cryptographically
         * secure random 32-byte private key for automated testing and CI handshakes.
         */
        fun createEphemeral(secureRandom: SecureRandom = CryptoUtils.secureRandom): ThesisSigner {
            val keyBytes = ByteArray(PRIVATE_KEY_BYTE_LENGTH)
            while (true) {
                secureRandom.nextBytes(keyBytes)
                val scalar = BigInteger(1, keyBytes)
                if (scalar > BigInteger.ZERO && scalar < SECP256K1_N) {
                    try {
                        return ThesisSigner(keyBytes)
                    } finally {
                        Arrays.fill(keyBytes, 0x00.toByte())
                    }
                }
            }
        }

        /**
         * Alias for [createEphemeral] to preserve backwards compatibility across existing call sites.
         */
        fun generateRandom(secureRandom: SecureRandom = CryptoUtils.secureRandom): ThesisSigner =
            createEphemeral(secureRandom)

        fun create(privateKey: ByteArray): ThesisSigner = ThesisSigner(privateKey)

        fun fromHex(hexChars: CharArray): ThesisSigner {
            val rawBytes = CryptoUtils.hexCharArrayToBytes(hexChars, PRIVATE_KEY_BYTE_LENGTH)
            try {
                return ThesisSigner(rawBytes)
            } finally {
                Arrays.fill(rawBytes, 0x00.toByte())
                Arrays.fill(hexChars, '\u0000')
            }
        }

        fun fromHex(hexPrivateKey: String): ThesisSigner = fromHex(hexPrivateKey.trim().toCharArray())
    }
}
