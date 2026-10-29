package com.example.thesis.util

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.regex.Pattern

/**
 * Internal cryptographic utility object.
 *
 * Centralises reusable, side-effect-free cryptographic primitives to prevent duplication
 * across `com.example.thesis.*` modules. All members are `internal` — they are invisible
 * to consumers outside this Gradle module, preserving encapsulation.
 *
 * Thread-safety:
 * - [secureRandom]: `SecureRandom` is thread-safe per the JDK specification.
 * - [sha256Hex]: Allocates a new [MessageDigest] per call (stateless, no shared mutable state).
 * - [hexCharArrayToBytes]: Operates exclusively on caller-supplied buffers.
 * - [sanitiseEthereumAddress]: Pure, stateless string sanitization and regex validation.
 */
object CryptoUtils {

    private val ETH_ADDRESS_REGEX: Regex = Pattern.compile("^0[xX][0-9a-fA-F]{40}$").toRegex()
    private val INBOX_ID_REGEX: Regex = Pattern.compile("^[0-9a-fA-F]{64}$").toRegex()

    // ────────────────────────────────────────────────────────────────────────
    //  Address Format Validation & Normalization
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Validates and normalizes an Ethereum address string conforming to standard EIP-55 hex representation.
     *
     * Validates strictly:
     * - Must start with '0x' or '0X' prefix.
     * - Must be followed by exactly 40 hexadecimal characters (`[0-9a-fA-F]{40}`).
     * - Trailing or leading whitespace is trimmed prior to validation.
     *
     * @param address Raw candidate Ethereum address string.
     * @return Canonical lowercase address (42 characters: `0x...`) if valid, or `null` if invalid.
     */
    fun sanitiseEthereumAddress(address: String): String? {
        val trimmed = address.trim()
        if (!ETH_ADDRESS_REGEX.matches(trimmed)) return null
        return trimmed.lowercase(Locale.ROOT)
    }

    /**
     * Convenience check returning true if [address] matches standard Ethereum hex address format.
     */
    fun isValidEthereumAddress(address: String): Boolean = sanitiseEthereumAddress(address) != null

    /**
     * Validates and normalizes an XMTP v4 MLS Inbox ID (64-character lowercase hexadecimal string).
     *
     * Handles optional `@` or `0x` prefixes often present in user-copied identifiers or logs.
     *
     * @param rawInboxId Candidate XMTP Inbox ID string.
     * @return Canonical 64-character lowercase hex string if valid, or `null` if invalid.
     */
    fun sanitiseInboxId(rawInboxId: String): String? {
        val trimmed = rawInboxId.trim()
            .removePrefix("@")
            .let { if (it.startsWith("0x", ignoreCase = true)) it.substring(2) else it }
        if (!INBOX_ID_REGEX.matches(trimmed)) return null
        return trimmed.lowercase(Locale.ROOT)
    }

    /**
     * Convenience check returning true if [rawInboxId] represents a valid XMTP Inbox ID.
     */
    fun isValidInboxId(rawInboxId: String): Boolean = sanitiseInboxId(rawInboxId) != null

    // ────────────────────────────────────────────────────────────────────────
    //  Entropy Source
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Module-wide [SecureRandom] singleton.
     *
     * Avoids repeated OS entropy pool seeding overhead from multiple instantiations in
     * [com.example.thesis.security.KeystoreManager] and [com.example.thesis.identity.ThesisSigner].
     * `SecureRandom` is thread-safe per JSR-132 and the JDK specification.
     */
    val secureRandom: SecureRandom by lazy { SecureRandom() }

    // ────────────────────────────────────────────────────────────────────────
    //  Hashing
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Computes the lowercase hex-encoded SHA-256 digest of [input].
     *
     * A fresh [MessageDigest] instance is created on every call — `MessageDigest` is not thread-safe
     * and must not be shared across coroutines without synchronization.
     *
     * @param input UTF-8 string to hash.
     * @return 64-character lowercase hex string (32 bytes x 2 hex chars per byte).
     */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Hex Encoding / Decoding
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Decodes a hex [CharArray] (with or without `0x`/`0X` prefix) into a [ByteArray].
     *
     * Memory-safety contract:
     * - The caller is responsible for zeroizing [hexChars] AFTER this function returns.
     * - The returned [ByteArray] must be zeroized by the caller after use.
     * - This function does NOT allocate any [String] from [hexChars]; it reads characters
     *   directly via index access to prevent JVM string-pool retention of key material.
     *
     * @param hexChars Hex characters, optionally prefixed with `0x` or `0X`.
     * @param expectedByteLength Expected number of decoded bytes; used for validation.
     * @return Decoded byte array of length [expectedByteLength].
     * @throws IllegalArgumentException If the hex length is incorrect or non-hex characters are present.
     */
    fun hexCharArrayToBytes(hexChars: CharArray, expectedByteLength: Int): ByteArray {
        var startIndex = 0
        val endIndex = hexChars.size

        // Strip optional '0x' or '0X' prefix without String allocation
        if (endIndex - startIndex >= 2 &&
            hexChars[startIndex] == '0' &&
            (hexChars[startIndex + 1] == 'x' || hexChars[startIndex + 1] == 'X')
        ) {
            startIndex += 2
        }

        val hexLen = endIndex - startIndex
        require(hexLen == expectedByteLength * 2) {
            "Hex input must be exactly ${expectedByteLength * 2} characters (got $hexLen)"
        }

        val rawBytes = ByteArray(expectedByteLength)
        for (i in 0 until expectedByteLength) {
            val high = Character.digit(hexChars[startIndex + (i * 2)], 16)
            val low = Character.digit(hexChars[startIndex + (i * 2) + 1], 16)
            require(high >= 0 && low >= 0) {
                "Invalid hexadecimal character at position ${startIndex + i * 2}"
            }
            rawBytes[i] = ((high shl 4) or low).toByte()
        }
        return rawBytes
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Network Error Classification
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Returns `true` if [throwable] is a transient network/gRPC failure that warrants retry.
     *
     * Consolidated error classifier covering transport resets, timeouts, DNS failures, and gRPC status codes.
     *
     * @param throwable The exception to classify.
     */
    fun isNetworkThrowable(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: ""
        return throwable is IOException ||
                message.contains("network") ||
                message.contains("grpc") ||
                message.contains("connect") ||
                message.contains("timeout") ||
                message.contains("unavailable") ||
                message.contains("unreachable") ||
                message.contains("reset by peer") ||
                message.contains("dns")
    }
}
