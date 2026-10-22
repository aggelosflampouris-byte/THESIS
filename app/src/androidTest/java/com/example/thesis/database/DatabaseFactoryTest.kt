package com.example.thesis.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Instrumented test suite for [DatabaseFactory] covering native SQLCipher 4.6.1 integration.
 *
 * Verifies:
 * 1. Ethereum address sanitization and lowercase hex normalization.
 * 2. Fail-closed rejection of path traversal and null-byte payloads.
 * 3. In-memory DEK zeroization immediately following native storage handoff.
 * 4. Automatic execution and schema validation via `PRAGMA integrity_check`.
 * 5. Native SQLCipher rejection when attempting to open a database with an invalid key.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseFactoryTest {

    companion object {
        private const val VALID_MIXED_CASE_ADDRESS: String = "0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B"
        private const val EXPECTED_NORMALIZED_ADDRESS: String = "0xab5801a7d398351b8be11c439e05c5b3259aec9b"
        private const val DEK_KEY_SIZE_BYTES: Int = 32
    }

    private lateinit var context: Context
    private lateinit var databaseFactory: DatabaseFactory
    private val secureRandom = SecureRandom()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        databaseFactory = DatabaseFactory(context)
        cleanupDatabaseFiles(EXPECTED_NORMALIZED_ADDRESS)
    }

    @After
    fun tearDown() {
        cleanupDatabaseFiles(EXPECTED_NORMALIZED_ADDRESS)
    }

    /**
     * Requirement 1: Validate mixed-case addresses format properly to lowercase hex.
     */
    @Test
    fun test_addressSanitization_validAddressesNormalized() {
        val sanitized = databaseFactory.sanitiseEthereumAddress(VALID_MIXED_CASE_ADDRESS)
        assertEquals("Mixed-case address must normalize to lowercase", EXPECTED_NORMALIZED_ADDRESS, sanitized)

        val withSpaces = databaseFactory.sanitiseEthereumAddress("  $VALID_MIXED_CASE_ADDRESS  ")
        assertEquals("Surrounding whitespace must be trimmed", EXPECTED_NORMALIZED_ADDRESS, withSpaces)

        val allCaps = "0X71C7656EC7AB88B098DEFB751B7401B5F6D8976F"
        assertEquals(
            "Uppercase prefix and body must lowercase properly",
            "0x71c7656ec7ab88b098defb751b7401b5f6d8976f",
            databaseFactory.sanitiseEthereumAddress(allCaps)
        )
    }

    /**
     * Requirement 2: Assert that inputs like "../../etc/passwd", null bytes, or invalid hex lengths
     * return null and fail closed.
     */
    @Test
    fun test_addressSanitization_pathTraversalRejected() {
        // Path traversal sequences
        assertNull(databaseFactory.sanitiseEthereumAddress("../../etc/passwd"))
        assertNull(databaseFactory.sanitiseEthereumAddress("0x1234/../../evil"))
        assertNull(databaseFactory.sanitiseEthereumAddress("../xmtp.db"))

        // Null-byte injection payloads
        assertNull(databaseFactory.sanitiseEthereumAddress("$VALID_MIXED_CASE_ADDRESS\u0000"))
        assertNull(databaseFactory.sanitiseEthereumAddress("0xAb5801\u0000a7D398351b8bE11C439e05C5B3259aeC9B"))

        // Malformed lengths (under / over 40 hex characters)
        assertNull(databaseFactory.sanitiseEthereumAddress("0x1234"))
        assertNull(databaseFactory.sanitiseEthereumAddress("0x" + "f".repeat(39)))
        assertNull(databaseFactory.sanitiseEthereumAddress("0x" + "f".repeat(41)))

        // Non-hex characters
        assertNull(databaseFactory.sanitiseEthereumAddress("0x" + "z".repeat(40)))

        // Missing 0x prefix
        assertNull(databaseFactory.sanitiseEthereumAddress(VALID_MIXED_CASE_ADDRESS.removePrefix("0x")))
    }

    /**
     * Requirement 3: Pass a clone of a DEK array; assert every byte is 0x00 immediately after
     * openEncryptedDatabase returns.
     */
    @Test
    fun test_dekZeroedAfterDatabaseHandoff() = runTest {
        val originalKey = ByteArray(DEK_KEY_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
        val keyClone = originalKey.clone()

        // Verify clone contains non-zero initial key material
        assertFalse("Key clone must contain non-zero initial key material", keyClone.all { it == 0.toByte() })

        val result = databaseFactory.openEncryptedDatabase(keyClone, VALID_MIXED_CASE_ADDRESS)
        assertTrue("Database opening must succeed", result is DatabaseResult.Success)

        // Assert that the input ByteArray was unconditionally wiped in-memory
        assertTrue(
            "Every byte of the input DEK buffer must be wiped to 0x00 after JNI handoff",
            keyClone.all { it == 0.toByte() }
        )

        // Cleanup opened database
        val db = (result as DatabaseResult.Success).value
        databaseFactory.closeDatabase(db)
    }

    /**
     * Requirement 4: Verify that PRAGMA integrity_check executes and confirms a healthy SQLite schema.
     */
    @Test
    fun test_databaseIntegrityCheckExecutesSuccessfully() = runTest {
        val key = ByteArray(DEK_KEY_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
        val result = databaseFactory.openEncryptedDatabase(key, VALID_MIXED_CASE_ADDRESS)

        assertTrue("Database opening must succeed and pass PRAGMA integrity_check", result is DatabaseResult.Success)
        val db = (result as DatabaseResult.Success).value

        // Verify database is usable, writable, and passes runtime integrity check
        db.execSQL("CREATE TABLE test_table (id INTEGER PRIMARY KEY, payload TEXT);")
        db.execSQL("INSERT INTO test_table (id, payload) VALUES (1, 'cryptographic_payload');")

        db.rawQuery("SELECT payload FROM test_table WHERE id = 1;", null).use { cursor ->
            assertTrue("Query on created table must return a row", cursor.moveToFirst())
            assertEquals("cryptographic_payload", cursor.getString(0))
        }

        db.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
            assertTrue("PRAGMA integrity_check must return at least one row", cursor.moveToNext())
            val status = cursor.getString(0)
            assertEquals("Integrity check must return 'ok'", "ok", status.lowercase())
        }

        databaseFactory.closeDatabase(db)
    }

    /**
     * Requirement 5: Attempt opening an existing database with a different 32-byte key;
     * assert DatabaseResult.Failure.OpenFailed is returned.
     */
    @Test
    fun test_wrongKeyFailsToOpenDatabase() = runTest {
        val validKey = ByteArray(DEK_KEY_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
        val wrongKey = ByteArray(DEK_KEY_SIZE_BYTES).apply { secureRandom.nextBytes(this) }

        // Step 1: Initialize encrypted database with the valid key and persist data to disk
        val initialResult = databaseFactory.openEncryptedDatabase(validKey, VALID_MIXED_CASE_ADDRESS)
        assertTrue("Initial database creation must succeed", initialResult is DatabaseResult.Success)
        val initialDb = (initialResult as DatabaseResult.Success).value

        initialDb.execSQL("CREATE TABLE secrets (data TEXT);")
        initialDb.execSQL("INSERT INTO secrets VALUES ('confidential_record');")
        databaseFactory.closeDatabase(initialDb)

        // Step 2: Attempt opening the existing encrypted file with the incorrect key
        val wrongKeyClone = wrongKey.clone()
        val failureResult = databaseFactory.openEncryptedDatabase(wrongKeyClone, VALID_MIXED_CASE_ADDRESS)

        assertTrue(
            "Opening database with wrong key must fail with OpenFailed, got: $failureResult",
            failureResult is DatabaseResult.Failure.OpenFailed
        )

        // Ensure key zeroization occurred even on failed open
        assertTrue(
            "Input wrongKey buffer must be zeroized even when native open fails",
            wrongKeyClone.all { it == 0.toByte() }
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun cleanupDatabaseFiles(sanitizedAddress: String) {
        try {
            val hash = sha256Hex(sanitizedAddress)
            val filename = "${DatabaseFactory.DB_NAME_PREFIX}$hash${DatabaseFactory.DB_EXTENSION}"
            val dbFile = context.getDatabasePath(filename)

            if (dbFile.exists()) {
                dbFile.delete()
            }
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            File("${dbFile.path}-journal").delete()
        } catch (_: Throwable) {
            // Ignore during setup/teardown reset
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
