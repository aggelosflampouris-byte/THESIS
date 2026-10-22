package com.example.thesis.identity

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
import com.example.thesis.util.CryptoUtils
import java.security.SecureRandom
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
 * Production-grade Ethereum identity signer implementing XMTP's [SigningKey]
 * and [AutoCloseable] with hardened defense-in-depth cryptographic controls.
 *
 * ## Cryptographic Invariants & Security Guarantees
 * 1. **EIP-2 / BIP-62 Non-Malleability**: Guarantees low-s canonicalization (`s <= N / 2`)
 *    with a strict post-signing assertion. Rejects or canonicalizes any high-s signatures
 *    to prevent third-party transaction and association malleability.
 * 2. **EIP-191 Personal Sign Standard**: Strictly constructs prefix headers using decimal ASCII
 *    length strings (`"\x19Ethereum Signed Message:\n" + message.length + message`), preventing
 *    type confusion and cross-context replay attacks.
 * 3. **Defensive Memory Lifecycle**:
 *    - Retains a single [ECKeyPair] reference to eliminate repeated [BigInteger] heap allocations
 *      per signature invocation.
 *    - In-place zeroization of [privateKeyBytes] and intermediate signing buffers on [close].
 *    - Provides [fromHex] overloads accepting [CharArray] to avoid immutable String pool retention.
 *    - Overrides [toString] to prevent inadvertent key disclosure in loggers or crash dumps.
 * 4. **JNI / UniFFI Boundary Alignment**: Produces strictly bounded 65-byte RSV byte arrays
 *    (`r` [32 bytes] || `s` [32 bytes] || `v` [1 byte, canonical 27 or 28]) conforming to
 *    `libxmtp` Rust core (`ethers-core::types::Signature`) expectations.
 */
class ThesisSigner(
    privateKey: ByteArray
) : SigningKey, AutoCloseable {

    private val lock = Any()

    @Volatile
    private var isClosed: Boolean = false

    private val privateKeyBytes: ByteArray

    /** Retained key pair instance to prevent heap churn of [BigInteger] instances on each sign call. */
    private var activeKeyPair: ECKeyPair?

    /**
     * Optional authorization hook to simulate or intercept interactive user consent.
     * When returning false, [sign] aborts and emits [UserRejectedSignatureException].
     */
    var userApprovalHandler: (() -> Boolean)? = null

    /** Canonical lowercase 0x-prefixed 42-character Ethereum address. */
    val address: String

    /** EIP-55 mixed-case checksummed Ethereum address. */
    val checksumAddress: String

    private val uncompressedPublicKeyBytes: ByteArray

    /** Defensive copy of the 65-byte uncompressed secp256k1 public key (0x04 || X || Y). */
    val uncompressedPublicKey: ByteArray
        get() = uncompressedPublicKeyBytes.clone()

    override val publicIdentity: PublicIdentity
    override val type: SignerType = SignerType.EOA
    override var chainId: Long? = null
    override var blockNumber: Long? = null

    init {
        require(privateKey.size == PRIVATE_KEY_BYTE_LENGTH) {
            "Private key must be exactly $PRIVATE_KEY_BYTE_LENGTH bytes, got ${privateKey.size}"
        }

        val scalar = BigInteger(1, privateKey)
        require(scalar > BigInteger.ZERO && scalar < SECP256K1_N) {
            "Private key scalar is outside valid secp256k1 range [1, N - 1]"
        }

        // Defensive clone isolates internal state from caller-side buffer modifications
        this.privateKeyBytes = privateKey.clone()

        // Derive public key and identity representations once during initialization
        val keyPair = ECKeyPair.create(this.privateKeyBytes)
        this.activeKeyPair = keyPair

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
     * Signs an arbitrary byte payload using the EIP-191 personal_sign standard:
     * `"\x19Ethereum Signed Message:\n" + message.length + message`.
     *
     * @param message Arbitrary byte array payload to be signed.
     * @return 65-byte canonical RSV signature: `r` (32 bytes) || `s` (32 bytes) || `v` (1 byte, 27 or 28).
     * @throws IllegalStateException If this signer has been closed and its private key scrubbed.
     * @throws UserRejectedSignatureException If [userApprovalHandler] is set and returns false.
     */
    fun sign(message: ByteArray): ByteArray {
        synchronized(lock) {
            check(!isClosed) { "ThesisSigner has been closed and private key material scrubbed" }

            val approved = userApprovalHandler?.invoke() ?: true
            if (!approved) {
                throw UserRejectedSignatureException("User rejected the signature request")
            }

            val keyPair = checkNotNull(activeKeyPair) { "Signer active key pair is uninitialized or destroyed" }

            // 1. EIP-191 Prefix Construction using decimal ASCII representation for length
            val lengthAscii = message.size.toString().toByteArray(StandardCharsets.US_ASCII)
            val prefixedPayload = ByteArray(EIP191_HEADER_BYTES.size + lengthAscii.size + message.size)

            try {
                System.arraycopy(EIP191_HEADER_BYTES, 0, prefixedPayload, 0, EIP191_HEADER_BYTES.size)
                System.arraycopy(lengthAscii, 0, prefixedPayload, EIP191_HEADER_BYTES.size, lengthAscii.size)
                System.arraycopy(message, 0, prefixedPayload, EIP191_HEADER_BYTES.size + lengthAscii.size, message.size)

                // 2. Keccak-256 cryptographic digest of the prefixed payload
                val messageDigest = Hash.sha3(prefixedPayload)

                // 3. SECP256K1 ECDSA signature generation (RFC 6979 deterministic k, EIP-2 low-s)
                val signatureData = Sign.signMessage(messageDigest, keyPair, false)

                // 4. BIP-62 / EIP-2 Non-Malleability Verification: Ensure low-s invariant strictly holds
                var sScalar = BigInteger(1, signatureData.s)
                var rawV = signatureData.v[0].toInt() and 0xFF

                if (sScalar > SECP256K1_HALF_N) {
                    // Invert s to lower half of the curve order and flip recovery ID parity
                    sScalar = SECP256K1_N.subtract(sScalar)
                    rawV = if (rawV == 27 || rawV == 0) 28 else 27
                }

                // Defensive post-condition asserting canonical low-s
                check(sScalar <= SECP256K1_HALF_N) {
                    "Signature s-value exceeds N/2: EIP-2 signature malleability violation"
                }

                // 5. Recovery ID v normalization to canonical values {27, 28}
                val canonicalV: Byte = when {
                    rawV == 0 || rawV == 1 -> (rawV + RECOVERY_ID_OFFSET).toByte()
                    rawV == 27 || rawV == 28 -> rawV.toByte()
                    rawV >= EIP155_V_THRESHOLD -> (((rawV - EIP155_V_THRESHOLD) % 2) + RECOVERY_ID_OFFSET).toByte()
                    else -> throw IllegalStateException("Non-canonical recovery ID v produced: $rawV")
                }

                // 6. Serialize into strictly bounded 65-byte RSV byte array
                val r = signatureData.r
                val s = Numeric.toBytesPadded(sScalar, COMPONENT_LENGTH)
                require(r.size == COMPONENT_LENGTH) { "r component must be exactly 32 bytes, got ${r.size}" }
                require(s.size == COMPONENT_LENGTH) { "s component must be exactly 32 bytes, got ${s.size}" }

                val rsv = ByteArray(SIGNATURE_RSV_LENGTH)
                System.arraycopy(r, 0, rsv, SIGNATURE_R_OFFSET, COMPONENT_LENGTH)
                System.arraycopy(s, 0, rsv, SIGNATURE_S_OFFSET, COMPONENT_LENGTH)
                rsv[SIGNATURE_V_OFFSET] = canonicalV

                return rsv
            } finally {
                // Scrub intermediate prefixed payload buffer from memory
                Arrays.fill(prefixedPayload, 0x00.toByte())
            }
        }
    }

    /**
     * Implements XMTP's [SigningKey.sign] interface method.
     * Encodes the challenge string to UTF-8, signs under EIP-191, and returns a [SignedData]
     * envelope containing the raw RSV signature and the uncompressed public key.
     *
     * @param message Challenge string provided by the XMTP network.
     * @return [SignedData] envelope ready for gRPC / Rust UniFFI consumption.
     * @throws UserRejectedSignatureException If [userApprovalHandler] is set and returns false.
     */
    override suspend fun sign(message: String): SignedData {
        val rawSignature = sign(message.toByteArray(StandardCharsets.UTF_8))
        return SignedData(
            rawData = rawSignature,
            publicKey = uncompressedPublicKeyBytes.clone(),
            authenticatorData = null,
            clientDataJson = null
        )
    }

    /**
     * Securely zeroizes internal private key byte buffers in place and releases key references.
     * Idempotent and thread-safe. Once closed, any subsequent attempt to sign throws [IllegalStateException].
     */
    override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                Arrays.fill(privateKeyBytes, 0x00.toByte())
                activeKeyPair = null
                isClosed = true
            }
        }
    }

    /** Returns true if this signer has been closed and its private key memory wiped. */
    val closed: Boolean
        get() = isClosed

    /**
     * Safe string representation omitting private key memory and PII.
     */
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
         * Creates a [ThesisSigner] from an existing 32-byte private key.
         */
        fun create(privateKey: ByteArray): ThesisSigner {
            return ThesisSigner(privateKey)
        }

        /**
         * Creates a [ThesisSigner] from a [CharArray] hex private key, zeroizing the character
         * buffer immediately upon initialization to prevent heap/string pool key retention.
         */
        fun fromHex(hexChars: CharArray): ThesisSigner {
            // Decoding delegated to CryptoUtils.hexCharArrayToBytes which preserves
            // identical memory-safety guarantees (no String allocation, no JVM pool retention).
            val rawBytes = CryptoUtils.hexCharArrayToBytes(hexChars, PRIVATE_KEY_BYTE_LENGTH)
            try {
                return ThesisSigner(rawBytes)
            } finally {
                // Scrub decoded key material and the caller-supplied hex buffer from heap memory.
                Arrays.fill(rawBytes, 0x00.toByte())
                Arrays.fill(hexChars, '\u0000')
            }
        }

        /**
         * Creates a [ThesisSigner] from a hex-encoded private key string (with or without '0x' prefix).
         * Note: [String] instances are immutable and cannot be scrubbed from JVM heap memory.
         * For zeroizable key lifecycles, prefer passing [CharArray] or [ByteArray].
         */
        fun fromHex(hexPrivateKey: String): ThesisSigner {
            val charArray = hexPrivateKey.trim().toCharArray()
            return fromHex(charArray)
        }

        /**
         * Generates a random [ThesisSigner] using [SecureRandom] for testing and transient sessions.
         * Ensures the intermediate generator buffer is wiped after construction.
         */
        fun generateRandom(secureRandom: SecureRandom = CryptoUtils.secureRandom): ThesisSigner {
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
    }
}
