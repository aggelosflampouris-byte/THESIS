package com.example.thesis.handshake

import android.content.Context
import android.util.Log
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
import com.example.thesis.xmtp.XmtpInitError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.xmtp.android.library.XMTPEnvironment
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-End Handshake Integration Test.
 *
 * Verifies cross-module coordination across the four primary identity and persistence pillars
 * on live Android runtime hardware / emulated sandbox:
 *
 * 1. [KeystoreManager]: Hardware KEK envelope provisioning in AndroidKeyStore -> AES-256 DEK unwrapping.
 * 2. [DatabaseFactory]: Isolated database path derivation -> SQLCipher schema integrity verification.
 * 3. [ThesisSigner]: Ephemeral ECDSA identity -> EIP-191 personal signature challenge (BIP-62 low-S, 65-byte RSV).
 * 4. [XmtpEngine]: Client options assembly -> [XMTPEnvironment.DEV] connection -> StateFlow lifecycle orchestration.
 */
@RunWith(AndroidJUnit4::class)
class XmtpHandshakeIntegrationTest {

    private lateinit var context: Context
    private lateinit var keystoreManager: KeystoreManager
    private lateinit var databaseFactory: DatabaseFactory
    private lateinit var engine: XmtpEngine
    private var testSigner: ThesisSigner? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        keystoreManager = KeystoreManager(context)
        databaseFactory = DatabaseFactory(context)
        cleanupEnvironment()
    }

    @After
    fun tearDown() {
        try {
            if (::engine.isInitialized) {
                engine.shutdown()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Teardown engine shutdown suppressed: ${e.message}")
        } finally {
            testSigner?.close()
            cleanupEnvironment()
        }
    }

    /**
     * Executes the complete four-step security and networking handshake sequence.
     */
    @Test
    fun test_endToEndHandshake_coordinatesAllComponents() = runTest(timeout = TEST_TIMEOUT) {
        // ====================================================================
        // Step 1: Keystore - Provisions Hardware KEK -> Extracts AES-256 DEK
        // ====================================================================
        val keyResult = keystoreManager.getOrCreateDatabaseKey()
        assertTrue(
            "Keystore provisioning must succeed with KeystoreResult.Success, got: $keyResult",
            keyResult is KeystoreResult.Success
        )
        val dek = (keyResult as KeystoreResult.Success).value
        assertEquals(
            "Unwrapped DEK must be exactly 32 bytes (256-bit AES)",
            KeystoreManager.DEK_SIZE_BYTES,
            dek.size
        )
        assertFalse(
            "Provisioned DEK buffer must not be all zeroes",
            dek.all { it == 0.toByte() }
        )

        // ====================================================================
        // Step 2: DatabaseFactory - Derives isolated DB path -> Verifies SQLCipher
        // ====================================================================
        val signer = ThesisSigner.generateRandom()
        testSigner = signer

        val sanitizedAddress = DatabaseFactory.sanitiseEthereumAddress(signer.address)
        assertNotNull("Signer Ethereum address must pass format sanitization", sanitizedAddress)

        val dbDirectory = databaseFactory.resolveAccountDatabaseDirectory(sanitizedAddress!!)
        assertTrue("Database directory path must be valid and isolated", dbDirectory.path.isNotEmpty())

        val dbFile = databaseFactory.resolveDatabaseFile(sanitizedAddress)
        assertTrue(
            "Resolved database file must reside within account database directory",
            dbFile.parentFile?.absolutePath == dbDirectory.absolutePath
        )

        // Test SQLCipher cryptographic initialization and schema integrity check
        val dekCloneForDb = dek.clone()
        val dbResult = databaseFactory.openEncryptedDatabase(dekCloneForDb, sanitizedAddress)
        assertTrue("SQLCipher open must succeed", dbResult is DatabaseResult.Success)
        val sqlCipherDb = (dbResult as DatabaseResult.Success).value

        try {
            sqlCipherDb.execSQL("CREATE TABLE IF NOT EXISTS handshake_audit (id INTEGER PRIMARY KEY, ts INTEGER);")
            sqlCipherDb.execSQL("INSERT INTO handshake_audit (id, ts) VALUES (1, 100);")

            sqlCipherDb.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
                assertTrue("PRAGMA integrity_check must return a result row", cursor.moveToNext())
                val status = cursor.getString(0)
                assertEquals("SQLCipher integrity check must return 'ok'", "ok", status.lowercase(Locale.ROOT))
            }
        } finally {
            databaseFactory.closeDatabase(sqlCipherDb)
        }

        // ====================================================================
        // Step 3: ThesisSigner - Signs EIP-191 identity challenge (BIP-62 low-S)
        // ====================================================================
        val challengeMessage = "XMTP : Identity Verification Challenge : ${System.currentTimeMillis()}"
        val signedData = signer.sign(challengeMessage)

        assertNotNull("SignedData envelope must not be null", signedData)
        assertEquals(
            "RSV signature payload must be strictly 65 bytes",
            SIGNATURE_RSV_LENGTH,
            signedData.rawData.size
        )

        // Verify public key format (65-byte uncompressed: 0x04 || X || Y)
        val pubKey = signedData.publicKey
        assertNotNull("SignedData must expose uncompressed public key", pubKey)
        assertEquals("Uncompressed public key must be 65 bytes", UNCOMPRESSED_KEY_LENGTH, pubKey!!.size)
        assertEquals("Public key must begin with 0x04 header byte", UNCOMPRESSED_HEADER_BYTE, pubKey[0])

        // Verify BIP-62 / EIP-2 low-S non-malleability invariant
        val sBytes = signedData.rawData.copyOfRange(SIGNATURE_S_OFFSET, SIGNATURE_V_OFFSET)
        val sScalar = BigInteger(1, sBytes)
        val curveHalfN = Sign.CURVE_PARAMS.n.shiftRight(1)
        assertTrue("Signature s-scalar must be positive", sScalar > BigInteger.ZERO)
        assertTrue("Signature s-scalar must satisfy canonical low-S (s <= N/2)", sScalar <= curveHalfN)

        // Verify canonical recovery byte {27, 28}
        val vByte = signedData.rawData[SIGNATURE_V_OFFSET]
        assertTrue(
            "Recovery ID v must be canonical 27 or 28, got: $vByte",
            vByte == CANONICAL_V_27 || vByte == CANONICAL_V_28
        )

        // Cryptographically recover signer address from signature to verify identity fidelity
        val rBytes = signedData.rawData.copyOfRange(SIGNATURE_R_OFFSET, SIGNATURE_S_OFFSET)
        val sigData = Sign.SignatureData(byteArrayOf(vByte), rBytes, sBytes)
        val recoveredKey = Sign.signedPrefixedMessageToKey(
            challengeMessage.toByteArray(StandardCharsets.UTF_8),
            sigData
        )
        val recoveredAddress = "0x" + Keys.getAddress(recoveredKey).lowercase(Locale.ROOT)
        assertEquals(
            "Recovered address from EIP-191 signature must match signer address",
            signer.address.lowercase(Locale.ROOT),
            recoveredAddress
        )

        // ====================================================================
        // Step 4: XmtpEngine - Initialises Client on XMTPEnvironment.DEV
        // ====================================================================
        engine = XmtpEngine(
            context = context,
            keystoreManager = keystoreManager,
            databaseFactory = databaseFactory,
            environment = XMTPEnvironment.DEV
        )

        assertEquals(
            "Engine must begin in Idle state prior to initialization",
            XmtpEngineState.Idle,
            engine.state.value
        )

        // Initiate asynchronous handshake pipeline
        engine.initialise(signer)

        // Observe lifecycle state transitions using Turbine
        engine.state.test(timeout = STATE_TRANSITION_TIMEOUT) {
            val initialState = awaitItem()
            // May be Idle or Loading depending on coroutine launch dispatch timing
            assertTrue(
                "Initial state before resolution must be Idle or Loading",
                initialState is XmtpEngineState.Idle || initialState is XmtpEngineState.Loading
            )

            var currentState = if (initialState is XmtpEngineState.Idle) awaitItem() else initialState
            assertEquals(
                "Engine must transition to Loading while provisioning native client",
                XmtpEngineState.Loading,
                currentState
            )

            // Await terminal state (Ready or NetworkFailure if offline sandbox)
            currentState = awaitItem()

            when (currentState) {
                is XmtpEngineState.Ready -> {
                    val client = currentState.client
                    assertNotNull("Active Client must be instantiated upon Ready transition", client)
                    assertTrue("Client inboxId must be non-empty", client.inboxId.isNotEmpty())
                    assertEquals("Engine client property must match Ready client", client, engine.client)
                    Log.i(TAG, "End-to-End handshake completed successfully with XMTP DEV node.")
                }

                is XmtpEngineState.Error -> {
                    val error = currentState.error
                    if (error is XmtpInitError.NetworkFailure) {
                        Log.w(
                            TAG,
                            "Handshake verified offline: Keystore, DatabaseFactory, and Signer succeeded. " +
                            "DEV network unreachable in current test sandbox: ${error.message}"
                        )
                        assertNotNull(
                            "Network failure must preserve root cause message",
                            error.cause.message
                        )
                    } else {
                        throw AssertionError("Handshake failed with unexpected error: $error", error.cause)
                    }
                }

                else -> {
                    throw AssertionError("Unexpected intermediate state emitted: $currentState")
                }
            }

            cancelAndIgnoreRemainingEvents()
        }

        // Verify clean shutdown
        engine.shutdown()
        assertEquals(
            "Engine state must reset to Idle following shutdown",
            XmtpEngineState.Idle,
            engine.state.value
        )
    }

    private fun cleanupEnvironment() {
        try {
            keystoreManager.deleteKeystoreEntry()
        } catch (_: Throwable) {}

        testSigner?.address?.let { addr ->
            try {
                val sanitized = DatabaseFactory.sanitiseEthereumAddress(addr)
                if (sanitized != null) {
                    val dbDir = databaseFactory.resolveAccountDatabaseDirectory(sanitized)
                    if (dbDir.exists()) {
                        dbDir.deleteRecursively()
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "HandshakeIntegrationTest"
        private const val SIGNATURE_RSV_LENGTH = 65
        private const val SIGNATURE_R_OFFSET = 0
        private const val SIGNATURE_S_OFFSET = 32
        private const val SIGNATURE_V_OFFSET = 64
        private const val UNCOMPRESSED_KEY_LENGTH = 65
        private const val UNCOMPRESSED_HEADER_BYTE: Byte = 0x04
        private const val CANONICAL_V_27: Byte = 27
        private const val CANONICAL_V_28: Byte = 28
        private val TEST_TIMEOUT = 60.seconds
        private val STATE_TRANSITION_TIMEOUT = 30.seconds
    }
}
