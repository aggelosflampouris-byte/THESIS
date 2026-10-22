package com.example.thesis.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.ProviderException
import java.security.SecureRandom
import com.example.thesis.util.CryptoUtils
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production-grade Keystore and Envelope Encryption Manager.
 *
 * Architecture & Cryptographic Hierarchy:
 * - Key Encryption Key (KEK): AES-256-GCM hardware-backed key residing strictly within
 *   the Android KeyStore secure hardware boundary (StrongBox on API 28+ if available,
 *   with transparent fallback to standard Trusted Execution Environment (TEE)).
 * - Data Encryption Key (DEK): High-entropy 256-bit (32-byte) cryptographic key generated
 *   via [SecureRandom], encrypted under the KEK via AES-256-GCM with a randomized 96-bit IV,
 *   and persisted synchronously to [SharedPreferences].
 *
 * Security Guarantees:
 * - Hardware Isolation & Non-Exportability: Key material never leaves the secure hardware boundary.
 *   Calling `encoded` on the KEK returns null. No wrapping or export capabilities are granted.
 * - Authenticated Encryption (AEAD): DEK ciphertext integrity is guaranteed by a 128-bit
 *   GCM authentication tag. Any tampering triggers [KeystoreResult.Failure.IntegrityViolation].
 * - Cross-Instance Thread-Safety: [getOrCreateDatabaseKey] synchronizes on a class-level companion
 *   lock to prevent multi-instance race conditions during DEK generation and file persistence.
 * - Memory Hygiene: Retains only the application context to prevent Activity leaks. Plaintext
 *   key buffers are zeroized if provisioning encounters an exception.
 */
class KeystoreManager(context: Context) {

    private val appContext: Context = context.applicationContext

    companion object {
        const val ANDROID_KEYSTORE_PROVIDER: String = "AndroidKeyStore"
        const val KEK_ALIAS: String = "thesis_db_kek_v1"
        const val PREF_FILE_NAME: String = "thesis_keystore_prefs"

        const val PREF_KEY_DEK_IV: String = "encrypted_dek_iv"
        const val PREF_KEY_DEK_CT: String = "encrypted_dek_ciphertext"

        private const val AES_GCM_TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS: Int = 256
        private const val GCM_TAG_LENGTH_BITS: Int = 128
        private const val GCM_IV_LENGTH_BYTES: Int = 12 // 96-bit IV per NIST SP 800-38D recommendation
        const val DEK_SIZE_BYTES: Int = 32      // 256-bit symmetric DEK

        /**
         * Global synchronization monitor. Prevents cross-instance concurrent calls from racing
         * to provision dual DEKs when multiple KeystoreManager instances are instantiated.
         */
        private val KEYSTORE_LOCK: Any = Any()

        @Volatile
        private var cachedPreferences: SharedPreferences? = null
    }

    // SecureRandom singleton delegated to CryptoUtils to avoid duplicate OS entropy pool seeding.
    private val secureRandom: SecureRandom get() = CryptoUtils.secureRandom

    /**
     * Synchronously retrieves or generates the 32-byte Data Encryption Key (DEK) used
     * to open or initialize the encrypted database.
     *
     * Execution Workflow:
     * 1. Resolves the hardware-backed KEK from Android KeyStore (creating it if absent).
     * 2. Inspects [SharedPreferences] for an existing encrypted DEK envelope (IV + ciphertext).
     * 3. If present: Decrypts and verifies DEK authenticity via AES-256-GCM.
     * 4. If absent: Generates a 32-byte random DEK, encrypts it with the KEK, and commits
     *    the envelope synchronously to disk before returning the plaintext key.
     *
     * @return [KeystoreResult.Success] containing the 32-byte DEK, or a typed [KeystoreResult.Failure].
     */
    fun getOrCreateDatabaseKey(): KeystoreResult<ByteArray> = synchronized(KEYSTORE_LOCK) {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            val kek: SecretKey = (keyStore.getKey(KEK_ALIAS, null) as? SecretKey)
                ?: generateHardwareKey(keyStore)

            val prefs = openEncryptedPreferences()
            val existingIvBase64 = prefs.getString(PREF_KEY_DEK_IV, null)
            val existingCtBase64 = prefs.getString(PREF_KEY_DEK_CT, null)

            if (existingIvBase64 != null && existingCtBase64 != null) {
                decryptDek(kek = kek, ivBase64 = existingIvBase64, ctBase64 = existingCtBase64)
            } else {
                provisionNewDek(kek = kek, prefs = prefs)
            }
        } catch (e: UserNotAuthenticatedException) {
            KeystoreResult.Failure.UserNotAuthenticated(
                message = "Device authentication is required to access hardware KeyStore.",
                cause = e
            )
        } catch (e: KeyStoreException) {
            KeystoreResult.Failure.KeystoreError(
                message = "Android KeyStore failure during database key access: ${e.message}",
                cause = e
            )
        } catch (e: GeneralSecurityException) {
            KeystoreResult.Failure.KeystoreError(
                message = "Cryptographic failure during database key access: ${e.message}",
                cause = e
            )
        } catch (e: IOException) {
            KeystoreResult.Failure.StorageError(
                message = "I/O failure accessing encrypted preferences storage: ${e.message}",
                cause = e
            )
        } catch (e: Exception) {
            KeystoreResult.Failure.Unknown(
                message = "Unexpected failure during database key provisioning: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Permanently destroys the hardware-backed KEK and purges the stored DEK envelope.
     * Once invoked, any database encrypted with this DEK becomes permanently inaccessible.
     *
     * @return [KeystoreResult.Success] with [Unit] on success, or a typed [KeystoreResult.Failure].
     */
    fun deleteKeystoreEntry(): KeystoreResult<Unit> = synchronized(KEYSTORE_LOCK) {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            cleanUpAliasQuietly(keyStore, KEK_ALIAS)

            val prefs = openEncryptedPreferences()
            val committed = prefs.edit().clear().commit()
            if (!committed) {
                KeystoreResult.Failure.StorageError(
                    message = "Failed to commit SharedPreferences erasure to disk.",
                    cause = IOException("SharedPreferences.commit() returned false during key purge")
                )
            } else {
                KeystoreResult.Success(Unit)
            }
        } catch (e: KeyStoreException) {
            KeystoreResult.Failure.KeystoreError(
                message = "Failed to purge KeyStore entry: ${e.message}",
                cause = e
            )
        } catch (e: Exception) {
            KeystoreResult.Failure.Unknown(
                message = "Unexpected error during Keystore entry purge: ${e.message}",
                cause = e
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  KEK Provisioning & Hardware Fallback
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Generates the AES-256-GCM KEK inside secure hardware.
     *
     * On API 28+, StrongBox backing is requested first. If StrongBox is unavailable,
     * unsupported, or throws [StrongBoxUnavailableException], [ProviderException],
     * [KeyStoreException], [InvalidAlgorithmParameterException], [NoSuchAlgorithmException],
     * or [SecurityException], the implementation transparently falls back to standard TEE.
     */
    private fun generateHardwareKey(keyStore: KeyStore): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(isStrongBox = true)
            } catch (e: Exception) {
                if (isStrongBoxFallbackEligible(e)) {
                    cleanUpAliasQuietly(keyStore, KEK_ALIAS)
                    return generateKey(isStrongBox = false)
                }
                throw e
            }
        }
        return generateKey(isStrongBox = false)
    }

    private fun generateKey(isStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        val spec = buildKeyGenParameterSpec(isStrongBox)
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Configures non-exportable hardware-backed KeyGenParameterSpec:
     * - Only ENCRYPT and DECRYPT purposes are granted (no WRAP_KEY, no EXPORT).
     * - AES-256-GCM with NoPadding.
     * - Randomized encryption enforced (Keystore generates secure IV internally).
     */
    private fun buildKeyGenParameterSpec(isStrongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isStrongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        return builder.build()
    }

    /**
     * Evaluates whether an exception during key generation warrants falling back from
     * StrongBox to standard TEE. Inspects the entire cause chain to catch nested OEM errors
     * (e.g., Samsung Knox KM_ERROR_UNIMPLEMENTED, Xiaomi/MediaTek unsupported HAL params).
     */
    private fun isStrongBoxFallbackEligible(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && current is StrongBoxUnavailableException) {
                return true
            }
            // Covers ProviderException, KeyStoreException, and OEM-specific HAL exceptions:
            // - Samsung Knox: TIMA / Keymaster KM_ERROR_UNIMPLEMENTED (-10003, -67)
            // - Xiaomi / MediaTek: IllegalArgumentException / IllegalStateException when StrongBox HAL is absent
            // - Generic Keymaster / Keymint unsupported parameter / operation failures
            if (current is ProviderException ||
                current is KeyStoreException ||
                current is InvalidAlgorithmParameterException ||
                current is NoSuchAlgorithmException ||
                current is SecurityException ||
                current is IllegalArgumentException ||
                current is IllegalStateException ||
                current is UnsupportedOperationException
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Unconditionally purges the alias to clear dirty or corrupt Keystore state
     * left by failed StrongBox attempts on OEM Keystore daemons (e.g. Knox).
     */
    private fun cleanUpAliasQuietly(keyStore: KeyStore, alias: String) {
        try {
            keyStore.deleteEntry(alias)
        } catch (_: Throwable) {
            // Silently suppress cleanup failures before retrying TEE generation
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  DEK Envelope Encryption / Decryption
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Generates a new 32-byte DEK, encrypts it under the hardware KEK, and persists
     * the envelope synchronously via SharedPreferences `.commit()`.
     *
     * Ensures strict memory hygiene: if encryption or persistence fails, in-memory
     * key material is zeroized immediately.
     */
    private fun provisionNewDek(
        kek: SecretKey,
        prefs: SharedPreferences
    ): KeystoreResult<ByteArray> {
        val plainDek = ByteArray(DEK_SIZE_BYTES).apply {
            secureRandom.nextBytes(this)
        }

        var persistenceSucceeded = false
        var ivBytes: ByteArray? = null
        var ctBytes: ByteArray? = null
        try {
            val (iv, ciphertext) = encryptDek(kek = kek, plainDek = plainDek)
            ivBytes = iv
            ctBytes = ciphertext

            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)

            val committed = prefs.edit()
                .putString(PREF_KEY_DEK_IV, ivBase64)
                .putString(PREF_KEY_DEK_CT, ctBase64)
                .commit()

            if (!committed) {
                return KeystoreResult.Failure.StorageError(
                    message = "Synchronous SharedPreferences commit failed while persisting DEK envelope.",
                    cause = IOException("SharedPreferences.commit() returned false")
                )
            }

            persistenceSucceeded = true
            return KeystoreResult.Success(plainDek)
        } finally {
            if (!persistenceSucceeded) {
                plainDek.fill(0)
            }
            ivBytes?.fill(0)
            ctBytes?.fill(0)
        }
    }

    private fun encryptDek(kek: SecretKey, plainDek: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, kek)
        }
        val ciphertext = cipher.doFinal(plainDek)
        val iv = cipher.iv ?: throw IllegalStateException("KeyStore Cipher did not supply an IV")
        return Pair(iv, ciphertext)
    }

    private fun decryptDek(
        kek: SecretKey,
        ivBase64: String,
        ctBase64: String
    ): KeystoreResult<ByteArray> {
        val iv = try {
            Base64.decode(ivBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return KeystoreResult.Failure.IntegrityViolation(
                message = "Base64 decoding failed for DEK envelope IV.",
                cause = e
            )
        }

        val ciphertext = try {
            Base64.decode(ctBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return KeystoreResult.Failure.IntegrityViolation(
                message = "Base64 decoding failed for DEK envelope ciphertext.",
                cause = e
            )
        }

        if (iv.size != GCM_IV_LENGTH_BYTES) {
            return KeystoreResult.Failure.IntegrityViolation(
                message = "Invalid IV length in stored DEK envelope. Expected $GCM_IV_LENGTH_BYTES bytes, got ${iv.size}.",
                cause = InvalidAlgorithmParameterException("Malformed GCM IV length")
            )
        }

        var activePlainDek: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
            val plainDek = cipher.doFinal(ciphertext)
            activePlainDek = plainDek

            if (plainDek.size != DEK_SIZE_BYTES) {
                plainDek.fill(0)
                activePlainDek = null
                return KeystoreResult.Failure.IntegrityViolation(
                    message = "Decrypted DEK size mismatch. Expected $DEK_SIZE_BYTES bytes, got ${plainDek.size}.",
                    cause = IllegalStateException("Corrupt DEK size")
                )
            }

            val resultDek = plainDek
            activePlainDek = null // Disarm scrubber on successful return
            KeystoreResult.Success(resultDek)
        } catch (e: AEADBadTagException) {
            // Explicitly handles GCM MAC tag authentication mismatch (ciphertext tampering or corruption)
            KeystoreResult.Failure.IntegrityViolation(
                message = "GCM authentication tag mismatch. DEK envelope has been tampered with or corrupted.",
                cause = e
            )
        } catch (e: BadPaddingException) {
            // Fallback for non-AEADBadTagException padding errors across heterogeneous OEM security providers
            KeystoreResult.Failure.IntegrityViolation(
                message = "Decryption padding failure during AEAD operation: ${e.message}",
                cause = e
            )
        } catch (e: IllegalBlockSizeException) {
            KeystoreResult.Failure.IntegrityViolation(
                message = "Invalid block size during AEAD decryption. Ciphertext is truncated or malformed.",
                cause = e
            )
        } catch (e: Exception) {
            KeystoreResult.Failure.KeystoreError(
                message = "Failed to decrypt DEK envelope: ${e.message}",
                cause = e
            )
        } finally {
            activePlainDek?.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Storage Provider
    // ────────────────────────────────────────────────────────────────────────

    private fun openEncryptedPreferences(): SharedPreferences {
        cachedPreferences?.let { return it }
        return synchronized(KEYSTORE_LOCK) {
            cachedPreferences ?: run {
                val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    appContext,
                    PREF_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { cachedPreferences = it }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Typed Result Monad
// ────────────────────────────────────────────────────────────────────────────

/**
 * Domain-specific result monad for Keystore operations.
 * Explicitly declared sealed class; does not declare a conflicting top-level typealias Result<T>.
 */
sealed class KeystoreResult<out T> {

    data class Success<T>(val value: T) : KeystoreResult<T>()

    sealed class Failure(
        open val message: String,
        open val cause: Throwable
    ) : KeystoreResult<Nothing>() {

        data class KeystoreError(
            override val message: String,
            override val cause: Throwable
        ) : Failure(message, cause)

        data class UserNotAuthenticated(
            override val message: String,
            override val cause: Throwable
        ) : Failure(message, cause)

        data class IntegrityViolation(
            override val message: String,
            override val cause: Throwable
        ) : Failure(message, cause)

        data class StorageError(
            override val message: String,
            override val cause: Throwable
        ) : Failure(message, cause)

        data class Unknown(
            override val message: String,
            override val cause: Throwable
        ) : Failure(message, cause)
    }

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw cause
    }

    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> default
    }

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    inline fun <R> map(transform: (T) -> R): KeystoreResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> KeystoreResult<R>): KeystoreResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): KeystoreResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (Failure) -> Unit): KeystoreResult<T> {
        if (this is Failure) action(this)
        return this
    }
}
