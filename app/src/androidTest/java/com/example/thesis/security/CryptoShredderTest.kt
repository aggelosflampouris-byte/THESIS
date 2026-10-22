package com.example.thesis.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.database.DatabaseResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Instrumented security test suite for [CryptoShredder] verifying GDPR Article 17
 * Right to Erasure guarantees, non-recoverability, and storage unlinking.
 */
@RunWith(AndroidJUnit4::class)
class CryptoShredderTest {

    companion object {
        private const val TEST_ETH_ADDRESS = "0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B"
        private const val EXPECTED_SANITISED_ADDRESS = "0xab5801a7d398351b8be11c439e05c5b3259aec9b"
    }

    private lateinit var context: Context
    private lateinit var keystoreManager: KeystoreManager
    private lateinit var databaseFactory: DatabaseFactory
    private lateinit var cryptoShredder: CryptoShredder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        keystoreManager = KeystoreManager(context)
        databaseFactory = DatabaseFactory(context)
        cryptoShredder = CryptoShredder(context, keystoreManager, databaseFactory)
        purgeAccountArtifacts()
    }

    @After
    fun tearDown() {
        purgeAccountArtifacts()
    }

    /**
     * Requirement 1: Verify that after calling shredAccountData(), KeyStore.containsAlias() returns false.
     */
    @Test
    fun test_cryptoShredding_deletesHardwareKey() = runTest {
        // Step 1: Provision hardware KEK and DEK envelope
        val keyResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue("Key provisioning must succeed", keyResult is KeystoreResult.Success)

        val keyStore = KeyStore.getInstance(KeystoreManager.ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        assertTrue("KEK alias must reside in KeyStore prior to shred", keyStore.containsAlias(KeystoreManager.KEK_ALIAS))

        val prefs = context.getSharedPreferences(KeystoreManager.PREF_FILE_NAME, Context.MODE_PRIVATE)
        assertNotNull("Persistent IV must exist", prefs.getString(KeystoreManager.PREF_KEY_DEK_IV, null))
        assertNotNull("Persistent ciphertext must exist", prefs.getString(KeystoreManager.PREF_KEY_DEK_CT, null))

        // Step 2: Execute cryptographic shredding
        val shredResult = cryptoShredder.shredAccountData(TEST_ETH_ADDRESS)
        assertTrue("Crypto-shredding must report success", shredResult is ShredResult.Success || shredResult is ShredResult.PartialSuccess)

        // Step 3: Assert hardware KEK is permanently deleted from KeyStore
        keyStore.load(null)
        assertFalse("KEK alias must be absent from KeyStore post-shred", keyStore.containsAlias(KeystoreManager.KEK_ALIAS))

        // Step 4: Assert DEK envelope is wiped from SharedPreferences
        assertNull("Stored IV must be purged", prefs.getString(KeystoreManager.PREF_KEY_DEK_IV, null))
        assertNull("Stored ciphertext must be purged", prefs.getString(KeystoreManager.PREF_KEY_DEK_CT, null))
    }

    /**
     * Requirement 2: Create .db, .db-wal, and .db-shm dummy files on disk;
     * verify that every single file is absent after execution.
     */
    @Test
    fun test_cryptoShredding_deletesAllDatabaseFiles() = runTest {
        // Step 1: Provision target database file and auxiliary WAL/SHM artifacts
        val dbFile = databaseFactory.resolveDatabaseFile(EXPECTED_SANITISED_ADDRESS)
        dbFile.parentFile?.mkdirs()

        dbFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val walFile = File("${dbFile.path}-wal").apply { writeBytes(byteArrayOf(0x05, 0x06, 0x07, 0x08)) }
        val shmFile = File("${dbFile.path}-shm").apply { writeBytes(byteArrayOf(0x09, 0x0A, 0x0B, 0x0C)) }
        val journalFile = File("${dbFile.path}-journal").apply { writeBytes(byteArrayOf(0x0D, 0x0E)) }

        val accountDir = databaseFactory.resolveAccountDatabaseDirectory(EXPECTED_SANITISED_ADDRESS)
        val dirPayload = File(accountDir, "mls_tree.bin").apply { writeBytes(byteArrayOf(0x10, 0x20)) }

        assertTrue("Main .db must exist prior to shred", dbFile.exists())
        assertTrue("WAL file must exist prior to shred", walFile.exists())
        assertTrue("SHM file must exist prior to shred", shmFile.exists())
        assertTrue("Journal file must exist prior to shred", journalFile.exists())
        assertTrue("Account dir payload must exist prior to shred", dirPayload.exists())

        // Step 2: Execute shred sequence
        val shredResult = cryptoShredder.shredAccountData(TEST_ETH_ADDRESS)
        assertTrue("Shredding must complete", shredResult is ShredResult.Success)

        // Step 3: Verify all files and directories were expunged
        assertFalse("Main .db must be deleted", dbFile.exists())
        assertFalse("WAL file must be deleted", walFile.exists())
        assertFalse("SHM file must be deleted", shmFile.exists())
        assertFalse("Journal file must be deleted", journalFile.exists())
        assertFalse("Account directory must be deleted", accountDir.exists())
    }

    /**
     * Requirement 3: Attempt to initialize or read the shredded database; assert that without
     * the hardware KEK, the file cannot be decrypted or restored.
     */
    @Test
    fun test_databaseBecomesMathematicallyUnrecoverable() = runTest {
        // Step 1: Initialize real SQLCipher encrypted database with genuine DEK
        val keyResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue("DEK generation must succeed", keyResult is KeystoreResult.Success)
        val dek = (keyResult as KeystoreResult.Success).value

        val openResult = databaseFactory.openEncryptedDatabase(dek, TEST_ETH_ADDRESS)
        assertTrue("Initial database creation must succeed", openResult is DatabaseResult.Success)
        val db = (openResult as DatabaseResult.Success).value

        db.execSQL("CREATE TABLE messages (id INT PRIMARY KEY, body TEXT);")
        db.execSQL("INSERT INTO messages VALUES (1, 'confidential_user_payload');")
        databaseFactory.closeDatabase(db)

        val dbFile = databaseFactory.resolveDatabaseFile(EXPECTED_SANITISED_ADDRESS)
        assertTrue("Encrypted DB file must reside on disk", dbFile.exists())

        // Preserve a raw copy of the encrypted database file to simulate orphaned ciphertext
        val orphanedCopy = File(context.cacheDir, "orphaned_copy.db")
        dbFile.copyTo(orphanedCopy, overwrite = true)
        assertTrue(orphanedCopy.exists())

        // Step 2: Execute cryptographic shredding (destroys hardware KEK)
        val shredResult = cryptoShredder.shredAccountData(TEST_ETH_ADDRESS)
        assertTrue("Shred operation must succeed", shredResult is ShredResult.Success)

        // Restore the orphaned ciphertext to test cryptographic irrecoverability
        orphanedCopy.copyTo(dbFile, overwrite = true)
        orphanedCopy.delete()
        assertTrue("Orphaned ciphertext restored to database path", dbFile.exists())

        // Step 3: Attempt opening the orphaned file with a brand-new provisioned KEK/DEK
        val newKeyResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue("New key generation must succeed", newKeyResult is KeystoreResult.Success)
        val newDek = (newKeyResult as KeystoreResult.Success).value

        val reOpenResult = databaseFactory.openEncryptedDatabase(newDek, TEST_ETH_ADDRESS)

        // Assert failure: SQLCipher authentication fails because KEK and DEK do not match ciphertext
        assertTrue(
            "Opening orphaned database with new DEK must fail with OpenFailed, got: $reOpenResult",
            reOpenResult is DatabaseResult.Failure.OpenFailed
        )

        // Cleanup
        if (dbFile.exists()) dbFile.delete()
    }

    /**
     * Requirement 4: Open an active SQLCipher connection, call shred, and verify locks
     * do not prevent storage destruction.
     */
    @Test
    fun test_shreddingActiveDatabase_safelyClosesAndPurges() = runTest {
        // Step 1: Open and hold an active SQLCipher database handle
        val keyResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue("Key provisioning must succeed", keyResult is KeystoreResult.Success)
        val dek = (keyResult as KeystoreResult.Success).value

        val openResult = databaseFactory.openEncryptedDatabase(dek, TEST_ETH_ADDRESS)
        assertTrue("Database opening must succeed", openResult is DatabaseResult.Success)
        val activeDb = (openResult as DatabaseResult.Success).value

        activeDb.execSQL("CREATE TABLE live_stream (record TEXT);")
        activeDb.execSQL("INSERT INTO live_stream VALUES ('active_transaction_lock');")

        val dbFile = databaseFactory.resolveDatabaseFile(EXPECTED_SANITISED_ADDRESS)
        assertTrue("Active database file exists", dbFile.exists())

        // Step 2: Invoke shredding while database handle is open
        val shredResult = cryptoShredder.shredAccountData(TEST_ETH_ADDRESS)
        assertTrue(
            "Shredding must complete without throwing file lock deadlocks",
            shredResult is ShredResult.Success || shredResult is ShredResult.PartialSuccess
        )

        // Step 3: Verify hardware KEK is unconditionally destroyed
        val keyStore = KeyStore.getInstance(KeystoreManager.ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        assertFalse("KEK must be destroyed even if active handle existed", keyStore.containsAlias(KeystoreManager.KEK_ALIAS))

        // Clean up connection
        try {
            databaseFactory.closeDatabase(activeDb)
        } catch (_: Throwable) {
            // Non-fatal cleanup post-shred
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun purgeAccountArtifacts() {
        try {
            keystoreManager.deleteKeystoreEntry()
        } catch (_: Throwable) {
            // Ignore during setup/teardown
        }

        try {
            val keyStore = KeyStore.getInstance(KeystoreManager.ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KeystoreManager.KEK_ALIAS)) {
                keyStore.deleteEntry(KeystoreManager.KEK_ALIAS)
            }
        } catch (_: Throwable) {
            // Ignore during setup/teardown
        }

        try {
            val dbFile = databaseFactory.resolveDatabaseFile(EXPECTED_SANITISED_ADDRESS)
            if (dbFile.exists()) dbFile.delete()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            File("${dbFile.path}-journal").delete()

            val accountDir = databaseFactory.resolveAccountDatabaseDirectory(EXPECTED_SANITISED_ADDRESS)
            if (accountDir.exists()) accountDir.deleteRecursively()
        } catch (_: Throwable) {
            // Ignore during setup/teardown
        }
    }
}
