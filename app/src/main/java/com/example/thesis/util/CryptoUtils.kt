package com.example.thesis.util

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

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
 */
object CryptoUtils {

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
     * Previously declared as a `private fun sha256Hex()` inside [com.example.thesis.database.DatabaseFactory].
     * Extracted here to enable reuse without exposing `DatabaseFactory` internals.
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
     * Previously inlined in [com.example.thesis.identity.ThesisSigner.fromHex].
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
     * Previously duplicated as `private fun isNetworkError()` in [com.example.thesis.xmtp.XmtpEngine]
     * and `private fun isRecoverableNetworkError()` in [com.example.thesis.repository.ConversationRepository],
     * with divergent keyword sets. This consolidated implementation is the union of both sets,
     * closing the classification gap.
     *
     * Matching is performed on the lowercased message string to avoid locale-dependent comparisons.
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
