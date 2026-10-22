package com.example.thesis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.database.DatabaseResult
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.security.KeystoreManager
import com.example.thesis.security.KeystoreResult
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmtp.android.library.XMTPEnvironment
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Instrumented integration test suite verifying the complete hardware-to-network pipeline:
 *
 * 1. Hardware Key: AES-256 DEK retrieval via [KeystoreManager.getOrCreateDatabaseKey].
 * 2. Encrypted DB: SQLCipher initialization and PRAGMA integrity verification via [DatabaseFactory.openEncryptedDatabase].
 * 3. Identity: Ephemeral key generation via [ThesisSigner.createEphemeral].
 * 4. Network Handshake: [XmtpEngine] instantiation on [XMTPEnvironment.DEV] and [XmtpEngine.initialise] invocation.
 * 5. State Verification: Flow assertion via Turbine transitioning from [XmtpEngineState.Loading] to [XmtpEngineState.Ready] within 15 seconds.
 * 6. Clean Teardown: [XmtpEngine.shutdown], native database handle closure, and verification of zero thread/JNI leaks.
 */
@RunWith(AndroidJUnit4::class)
class XmtpIntegrationTest {

    private lateinit var context: Context
    private lateinit var keystoreManager: KeystoreManager
    private lateinit var databaseFactory: DatabaseFactory
    private lateinit var engine: XmtpEngine

    private var openDb: SQLiteDatabase? = null
    private var testSigner: ThesisSigner? = null
    private var ephemeralDbAddress: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        keystoreManager = KeystoreManager(context)
        databaseFactory = DatabaseFactory(context)
        purgeTestArtifacts()
    }

    @After
    fun tearDown() = runTest {
        try {
            if (::engine.isInitialized) {
                engine.shutdown()
            }
        } catch (_: Throwable) {
        }

        try {
            val db = openDb
            if (db != null && db.isOpen) {
                databaseFactory.closeDatabase(db)
            }
        } catch (_: Throwable) {
        } finally {
            openDb = null
        }

        try {
            testSigner?.close()
        } catch (_: Throwable) {
        } finally {
            testSigner = null
        }

        purgeTestArtifacts()
    }

    /**
     * Executes the end-to-end hardware-to-network pipeline integration test.
     */
    @Test
    fun test_completeHardwareToNetworkPipeline() = runTest(timeout = TEST_TIMEOUT) {
        // ====================================================================
        // Step 1 (Hardware Key): Retrieve 32-byte DEK from KeystoreManager
        // ====================================================================
        val keyResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue(
            "KeystoreManager.getOrCreateDatabaseKey() must return KeystoreResult.Success, got: $keyResult",
            keyResult is KeystoreResult.Success
        )
        val dek = (keyResult as KeystoreResult.Success).value
        assertEquals(
            "Derived DEK must be strictly 32 bytes (256-bit AES-GCM envelope)",
            KeystoreManager.DEK_SIZE_BYTES,
            dek.size
        )
        assertFalse(
            "Provisioned DEK buffer must not be all zeroes",
            dek.all { it == 0.toByte() }
        )

        // ====================================================================
        // Step 2 (Encrypted DB): Initialize SQLCipher DB for ephemeral address
        // ====================================================================
        val tempSigner = ThesisSigner.createEphemeral()
        val ephemeralAddress = tempSigner.address
        ephemeralDbAddress = ephemeralAddress
        tempSigner.close()

        // DatabaseFactory.openEncryptedDatabase zeroes the input DEK buffer; pass a copy
        val dekCloneForDb = dek.copyOf()
        val dbResult = databaseFactory.openEncryptedDatabase(dekCloneForDb, ephemeralAddress)
        assertTrue(
            "DatabaseFactory.openEncryptedDatabase() must succeed with DatabaseResult.Success, got: $dbResult",
            dbResult is DatabaseResult.Success
        )
        val sqlCipherDb = (dbResult as DatabaseResult.Success).value
        openDb = sqlCipherDb
        assertTrue("SQLCipher database instance must be open", sqlCipherDb.isOpen)

        // Verify cryptographic integrity using SQLCipher pragma
        sqlCipherDb.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
            assertTrue("PRAGMA integrity_check must return a result cursor", cursor.moveToNext())
            val checkStatus = cursor.getString(0)
            assertEquals(
                "SQLCipher integrity check verification must return 'ok'",
                "ok",
                checkStatus.lowercase(Locale.ROOT)
            )
        }

        // ====================================================================
        // Step 3 (Identity): Create a ThesisSigner.createEphemeral() instance
        // ====================================================================
        val signer = ThesisSigner.createEphemeral()
        testSigner = signer

        assertNotNull("Signer Ethereum address must not be null", signer.address)
        assertTrue("Signer address must start with '0x'", signer.address.startsWith("0x"))
        assertEquals("Ethereum address must be exactly 42 characters (0x + 40 hex)", 42, signer.address.length)
        assertFalse("Signer must be active and not closed upon creation", signer.closed)
        assertEquals("Uncompressed public key must be exactly 65 bytes", 65, signer.uncompressedPublicKey.size)
        assertEquals("Uncompressed public key header byte must be 0x04", 0x04.toByte(), signer.uncompressedPublicKey[0])

        // ====================================================================
        // Step 4 (Network Handshake): Instantiate XmtpEngine & call initialise()
        // ====================================================================
        engine = XmtpEngine(
            context = context,
            keystoreManager = keystoreManager,
            databaseFactory = databaseFactory,
            environment = XMTPEnvironment.DEV
        )
        assertEquals(
            "Engine state must start in Idle prior to initialise() invocation",
            XmtpEngineState.Idle,
            engine.state.value
        )
        assertNull("Engine client property must be null prior to initialisation", engine.client)

        // Initiate the asynchronous hardware-to-network handshake
        engine.initialise(signer)

        // ====================================================================
        // Step 5 (State Verification): Transitions from Loading to Ready in 15s
        // ====================================================================
        engine.state.test(timeout = STATE_VERIFICATION_TIMEOUT) {
            val initialItem = awaitItem()
            val loadingState = if (initialItem is XmtpEngineState.Idle) awaitItem() else initialItem
            assertEquals(
                "Engine must transition into Loading state while provisioning native client",
                XmtpEngineState.Loading,
                loadingState
            )

            when (val terminalState = awaitItem()) {
                is XmtpEngineState.Ready -> {
                    val client = terminalState.client
                    assertNotNull("Client must be instantiated upon Ready transition", client)
                    assertTrue("Client inbox ID must not be empty", client.inboxId.isNotEmpty())
                    assertEquals(
                        "Engine client accessor must reflect active Ready client",
                        client,
                        engine.client
                    )
                }
                is XmtpEngineState.Error -> {
                    fail(
                        "Engine transitioned to Error instead of Ready within 15 seconds: " +
                            "${terminalState.error.message} (cause: ${terminalState.error.cause})"
                    )
                }
                else -> {
                    fail("Unexpected state emitted from XmtpEngine.state: $terminalState")
                }
            }
            cancelAndIgnoreRemainingEvents()
        }

        // ====================================================================
        // Step 6 (Clean Teardown): Shutdown engine, close DB, verify no leaks
        // ====================================================================
        // 6a. Shutdown engine and reset state
        engine.shutdown()
        assertEquals(
            "Engine state must reset to Idle immediately following shutdown()",
            XmtpEngineState.Idle,
            engine.state.value
        )
        assertNull(
            "Engine client property must be cleared to null upon shutdown()",
            engine.client
        )

        // 6b. Close SQLCipher database connection
        databaseFactory.closeDatabase(sqlCipherDb)
        assertFalse(
            "SQLCipher database connection must be closed without dangling file handles",
            sqlCipherDb.isOpen
        )
        openDb = null

        // 6c. Zeroize signer memory
        signer.close()
        assertTrue("Ephemeral signer must report closed and memory zeroized", signer.closed)

        // 6d. Verify coroutine lifecycle cleanup within engineScope
        val coroutinesDrained = withTimeoutOrNull(5.seconds) {
            while (engine.hasActiveJobs) {
                delay(50)
            }
            true
        } ?: false
        assertTrue(
            "All active coroutine jobs within XmtpEngine.engineScope must terminate cleanly",
            coroutinesDrained
        )

        // 6e. Verify native threads and JNI worker handles do not leak
        val activeLeakedThreads = withTimeoutOrNull(5.seconds) {
            while (true) {
                val matching = Thread.getAllStackTraces().keys.filter { thread ->
                    thread.isAlive &&
                        (thread.name.contains("xmtp", ignoreCase = true) ||
                            thread.name.contains("tokio", ignoreCase = true) ||
                            thread.name.contains("sqlcipher", ignoreCase = true)) &&
                        !thread.name.contains("junit", ignoreCase = true)
                }
                if (matching.isEmpty()) return@withTimeoutOrNull emptyList<Thread>()
                delay(100)
            }
            emptyList<Thread>()
        } ?: Thread.getAllStackTraces().keys.filter { thread ->
            thread.isAlive &&
                (thread.name.contains("xmtp", ignoreCase = true) ||
                    thread.name.contains("tokio", ignoreCase = true) ||
                    thread.name.contains("sqlcipher", ignoreCase = true)) &&
                !thread.name.contains("junit", ignoreCase = true)
        }

        assertTrue(
            "No dangling XMTP or SQLCipher JNI threads should leak post-shutdown. Leaked: ${activeLeakedThreads.map { it.name }}",
            activeLeakedThreads.isEmpty()
        )
    }

    private fun purgeTestArtifacts() {
        try {
            keystoreManager.deleteKeystoreEntry()
        } catch (_: Throwable) {
        }

        testSigner?.address?.let { address ->
            try {
                val sanitized = DatabaseFactory.sanitiseEthereumAddress(address)
                if (sanitized != null) {
                    val dbDir = databaseFactory.resolveAccountDatabaseDirectory(sanitized)
                    if (dbDir.exists()) {
                        dbDir.deleteRecursively()
                    }
                    val dbFile = databaseFactory.resolveDatabaseFile(sanitized)
                    if (dbFile.exists()) {
                        dbFile.delete()
                    }
                }
            } catch (_: Throwable) {
            }
        }

        ephemeralDbAddress?.let { address ->
            try {
                val sanitized = DatabaseFactory.sanitiseEthereumAddress(address)
                if (sanitized != null) {
                    val dbFile = databaseFactory.resolveDatabaseFile(sanitized)
                    if (dbFile.exists()) {
                        dbFile.delete()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private val TEST_TIMEOUT = 30.seconds
        private val STATE_VERIFICATION_TIMEOUT = 15.seconds
    }
}
