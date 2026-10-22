package com.example.thesis.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented security and concurrency test suite for [KeystoreManager].
 *
 * Verifies:
 * 1. Deterministic DEK resolution across distinct invocations and manager instances.
 * 2. Cross-instance thread safety under concurrent asynchronous execution (10 coroutines).
 * 3. AEAD authentication tag validation and tampering rejection via [KeystoreResult.Failure.IntegrityViolation].
 * 4. Cryptographic shredding and persistent storage hygiene via [KeystoreManager.deleteKeystoreEntry].
 */
@RunWith(AndroidJUnit4::class)
class KeystoreManagerTest {

    private lateinit var context: Context
    private lateinit var keystoreManager: KeystoreManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        keystoreManager = KeystoreManager(context)
        purgeKeystoreAndPreferences()
    }

    @After
    fun tearDown() {
        purgeKeystoreAndPreferences()
    }

    /**
     * Requirement 1: Verify subsequent calls return the exact same 32-byte DEK without regenerating it.
     */
    @Test
    fun test_getOrCreateDatabaseKey_returnsDeterministicKeyAcrossInvocations() {
        val firstResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue("First key retrieval must succeed", firstResult is KeystoreResult.Success)
        val initialKey = (firstResult as KeystoreResult.Success).value
        assertEquals("Initial DEK must be exactly 32 bytes (256-bit)", KeystoreManager.DEK_SIZE_BYTES, initialKey.size)

        // Subsequent call on the same instance
        val secondResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue("Second key retrieval must succeed", secondResult is KeystoreResult.Success)
        val subsequentKey = (secondResult as KeystoreResult.Success).value
        assertArrayEquals("Subsequent call must return identical DEK bytes", initialKey, subsequentKey)

        // Call on a newly instantiated manager instance to ensure persistent envelope fidelity
        val newManager = KeystoreManager(context)
        val thirdResult = newManager.getOrCreateDatabaseKey()
        assertTrue("New manager key retrieval must succeed", thirdResult is KeystoreResult.Success)
        val newInstanceKey = (thirdResult as KeystoreResult.Success).value
        assertArrayEquals("Distinct KeystoreManager instance must resolve identical DEK bytes", initialKey, newInstanceKey)
    }

    /**
     * Requirement 2: Spin up 10 concurrent coroutines calling getOrCreateDatabaseKey() simultaneously;
     * assert all receive identical DEK bytes.
     */
    @Test
    fun test_concurrentKeyRetrieval_threadSafety() = runTest {
        val concurrentWorkerCount = 10

        val deferredResults = (1..concurrentWorkerCount).map {
            async(Dispatchers.IO) {
                // Instantiate distinct manager instances across threads to stress-test static monitor
                val workerManager = KeystoreManager(context)
                workerManager.getOrCreateDatabaseKey()
            }
        }

        val results = deferredResults.awaitAll()
        assertEquals(concurrentWorkerCount, results.size)

        val firstResult = results.first()
        assertTrue("Initial worker call must succeed", firstResult is KeystoreResult.Success)
        val benchmarkKey = (firstResult as KeystoreResult.Success).value
        assertEquals(KeystoreManager.DEK_SIZE_BYTES, benchmarkKey.size)

        results.forEachIndexed { index, result ->
            assertTrue("Worker $index must return KeystoreResult.Success", result is KeystoreResult.Success)
            val workerKey = (result as KeystoreResult.Success).value
            assertArrayEquals("Worker $index resolved a divergent DEK buffer (race condition detected)", benchmarkKey, workerKey)
        }
    }

    /**
     * Requirement 3: Mutate the stored Base64 ciphertext in SharedPreferences;
     * verify the call returns KeystoreResult.Failure.IntegrityViolation.
     */
    @Test
    fun test_tamperedCiphertext_failsWithIntegrityViolation() {
        val provisioningResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue("Initial provisioning must succeed", provisioningResult is KeystoreResult.Success)

        val prefs = openTestPreferences()
        val originalCiphertextBase64 = prefs.getString(KeystoreManager.PREF_KEY_DEK_CT, null)
        assertNotNull("Ciphertext must be present in SharedPreferences", originalCiphertextBase64)

        // Mutate ciphertext byte stream to simulate tampering
        val ciphertextBytes = Base64.decode(originalCiphertextBase64, Base64.NO_WRAP)
        assertTrue("Ciphertext byte buffer must not be empty", ciphertextBytes.isNotEmpty())
        ciphertextBytes[0] = (ciphertextBytes[0].toInt() xor 0x5A).toByte()

        val tamperedCiphertextBase64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
        val commitSuccess = prefs.edit()
            .putString(KeystoreManager.PREF_KEY_DEK_CT, tamperedCiphertextBase64)
            .commit()
        assertTrue("Writing tampered ciphertext to SharedPreferences must succeed", commitSuccess)

        // Retrieval must detect authentication tag mismatch
        val tamperedResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue(
            "Retrieval of tampered ciphertext must fail with IntegrityViolation, got: $tamperedResult",
            tamperedResult is KeystoreResult.Failure.IntegrityViolation
        )

        val failure = tamperedResult as KeystoreResult.Failure.IntegrityViolation
        assertNotNull("IntegrityViolation must contain root cause exception", failure.cause)
    }

    /**
     * Requirement 4: Call deleteKeystoreEntry(); assert alias is absent from KeyStore and
     * SharedPreferences is empty.
     */
    @Test
    fun test_deleteKeystoreEntry_removesKeyAndClearsPreferences() {
        val provisioningResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue("Initial provisioning must succeed", provisioningResult is KeystoreResult.Success)

        val keyStore = KeyStore.getInstance(KeystoreManager.ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        assertTrue("KEK alias must reside in KeyStore before deletion", keyStore.containsAlias(KeystoreManager.KEK_ALIAS))

        val prefs = openTestPreferences()
        assertNotNull("DEK IV must exist before deletion", prefs.getString(KeystoreManager.PREF_KEY_DEK_IV, null))
        assertNotNull("DEK ciphertext must exist before deletion", prefs.getString(KeystoreManager.PREF_KEY_DEK_CT, null))

        // Execute cryptographic purge
        val deleteResult = keystoreManager.deleteKeystoreEntry()
        assertTrue("Purge operation must succeed", deleteResult is KeystoreResult.Success)

        // Verify KeyStore entry is destroyed
        keyStore.load(null)
        assertFalse("KEK alias must be destroyed in KeyStore post-deletion", keyStore.containsAlias(KeystoreManager.KEK_ALIAS))

        // Verify persistent preferences are wiped
        assertNull("DEK IV must be purged from SharedPreferences", prefs.getString(KeystoreManager.PREF_KEY_DEK_IV, null))
        assertNull("DEK ciphertext must be purged from SharedPreferences", prefs.getString(KeystoreManager.PREF_KEY_DEK_CT, null))
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Test Utilities
    // ────────────────────────────────────────────────────────────────────────

    private fun openTestPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            KeystoreManager.PREF_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun purgeKeystoreAndPreferences() {
        try {
            keystoreManager.deleteKeystoreEntry()
        } catch (_: Throwable) {
            // Ignore during setup/teardown reset
        }

        try {
            val keyStore = KeyStore.getInstance(KeystoreManager.ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KeystoreManager.KEK_ALIAS)) {
                keyStore.deleteEntry(KeystoreManager.KEK_ALIAS)
            }
        } catch (_: Throwable) {
            // Ignore during setup/teardown reset
        }

        try {
            openTestPreferences().edit().clear().commit()
        } catch (_: Throwable) {
            // Ignore during setup/teardown reset
        }
    }
}
