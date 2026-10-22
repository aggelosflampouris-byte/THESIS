package com.example.thesis.xmtp

import android.content.Context
import android.util.Log
import app.cash.turbine.test
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.identity.UserRejectedSignatureException
import com.example.thesis.security.KeystoreManager
import com.example.thesis.security.KeystoreResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmtp.android.library.Client
import org.xmtp.android.library.DelicateApi
import org.xmtp.android.library.XMTPEnvironment
import uniffi.xmtpv3.InternalException
import java.io.File
import java.io.IOException

/**
 * Unit test suite verifying asynchronous concurrency, lifecycle transitions,
 * error boundary mapping, and shutdown guarantees in [XmtpEngine].
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateApi::class)
class XmtpEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockContext: Context
    private lateinit var mockKeystoreManager: KeystoreManager
    private lateinit var mockDatabaseFactory: DatabaseFactory
    private lateinit var realSigner: ThesisSigner
    private lateinit var mockClient: Client
    private lateinit var testDbDir: File
    private val testDek = ByteArray(32) { 0x42.toByte() }

    private lateinit var engine: XmtpEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(Client.Companion)

        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext

        testDbDir = tempFolder.newFolder("xmtp_test_db")
        mockDatabaseFactory = mockk()
        every { mockDatabaseFactory.resolveAccountDatabaseDirectory(any()) } returns testDbDir

        mockKeystoreManager = mockk()
        every { mockKeystoreManager.getOrCreateDatabaseKey() } returns KeystoreResult.Success(testDek.copyOf())

        realSigner = ThesisSigner.generateRandom()

        mockClient = mockk(relaxed = true)
        every { mockClient.inboxId } returns "inbox_test_12345"
        every { mockClient.dropLocalDatabaseConnection() } returns Unit

        coEvery { Client.create(any(), any()) } returns mockClient

        engine = XmtpEngine(
            context = mockContext,
            keystoreManager = mockKeystoreManager,
            databaseFactory = mockDatabaseFactory,
            environment = XMTPEnvironment.LOCAL,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
        realSigner.close()
    }

    /**
     * Requirement 1:
     * Assert that before initialization, engine.state.value is XmtpEngineState.Idle.
     */
    @Test
    fun test_initialState_isIdle() {
        assertEquals("Initial state must be Idle", XmtpEngineState.Idle, engine.state.value)
        assertNull("Client property must be null in Idle state", engine.client)
        assertFalse("No background jobs should be running initially", engine.hasActiveJobs)
    }

    /**
     * Requirement 2:
     * Mock Client.create to return a mock client; verify state transitions cleanly:
     * Idle -> Loading -> Ready.
     */
    @Test
    fun test_successfulInitialization_transitionsToReady() = runTest(testDispatcher) {
        engine.state.test {
            // Initial state
            assertEquals(XmtpEngineState.Idle, awaitItem())

            // Trigger initialization
            engine.initialise(realSigner)

            // State immediately transitions to Loading
            assertEquals(XmtpEngineState.Loading, awaitItem())

            // Advance virtual time to complete asynchronous Client.create
            testScheduler.advanceUntilIdle()

            // State transitions cleanly to Ready
            val readyState = awaitItem()
            assertTrue("State must transition to Ready", readyState is XmtpEngineState.Ready)
            val client = (readyState as XmtpEngineState.Ready).client
            assertEquals(mockClient, client)
            assertEquals("inbox_test_12345", client.inboxId)

            assertEquals("engine.client property must match active client", mockClient, engine.client)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { Client.create(account = realSigner, options = any()) }
    }

    /**
     * Requirement 3:
     * Mock signer throwing signature rejection; assert state terminates in
     * XmtpEngineState.Error(XmtpInitError.SignatureRejected).
     */
    @Test
    fun test_signatureRejection_transitionsToSignatureRejectedError() = runTest(testDispatcher) {
        val rejectionException = UserRejectedSignatureException("User clicked cancel on EIP-191 sign prompt")
        coEvery { Client.create(any(), any()) } throws rejectionException

        engine.state.test {
            assertEquals(XmtpEngineState.Idle, awaitItem())

            engine.initialise(realSigner)
            assertEquals(XmtpEngineState.Loading, awaitItem())

            testScheduler.advanceUntilIdle()

            val errorState = awaitItem()
            assertTrue("State must be Error", errorState is XmtpEngineState.Error)
            val error = (errorState as XmtpEngineState.Error).error
            assertTrue(
                "Error must be SignatureRejected",
                error is XmtpInitError.SignatureRejected
            )
            assertEquals(rejectionException, error.cause)
            assertTrue(error.message.contains("User clicked cancel"))

            assertNull("Client must remain null upon error", engine.client)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Requirement 4:
     * Call initialise() multiple times concurrently; assert client creation runs exactly once.
     */
    @Test
    fun test_idempotentInitialization_singleCallExecuted() = runTest(testDispatcher) {
        engine.state.test {
            assertEquals(XmtpEngineState.Idle, awaitItem())

            // Rapid consecutive / concurrent calls from multiple coroutines
            repeat(10) {
                launch {
                    engine.initialise(realSigner)
                }
            }

            assertEquals(XmtpEngineState.Loading, awaitItem())

            testScheduler.advanceUntilIdle()

            val readyState = awaitItem()
            assertTrue("State must reach Ready", readyState is XmtpEngineState.Ready)

            cancelAndIgnoreRemainingEvents()
        }

        // Client.create must execute strictly once despite 10 concurrent requests
        coVerify(exactly = 1) { Client.create(account = realSigner, options = any()) }

        // Additional initialise() call when already Ready must also be a no-op
        engine.initialise(realSigner)
        testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { Client.create(account = realSigner, options = any()) }
    }

    /**
     * Requirement 5:
     * Initialize, call shutdown(), and assert state returns to Idle while background jobs terminate.
     */
    @Test
    fun test_shutdown_resetsStateToIdleAndCancelsScope() = runTest(testDispatcher) {
        engine.initialise(realSigner)
        testScheduler.advanceUntilIdle()

        assertTrue("Engine must be Ready before shutdown", engine.state.value is XmtpEngineState.Ready)
        assertEquals(mockClient, engine.client)

        // Initiate shutdown
        engine.shutdown()

        // Synchronously state is reset to Idle and client reference is cleared
        assertEquals("State must reset to Idle immediately on shutdown", XmtpEngineState.Idle, engine.state.value)
        assertNull("Client reference must be nulled on shutdown", engine.client)

        // Advance dispatcher to execute asynchronous native connection drop
        testScheduler.advanceUntilIdle()

        // Verify native cleanup was dispatched
        verify(exactly = 1) { mockClient.dropLocalDatabaseConnection() }

        // Assert all background coroutine jobs have terminated
        assertFalse("Background engine scope children must terminate after shutdown", engine.hasActiveJobs)
    }

    /**
     * Verifies that network/gRPC unreachable failures map to [XmtpInitError.NetworkFailure].
     */
    @Test
    fun test_networkFailure_transitionsToNetworkFailureError() = runTest(testDispatcher) {
        val networkException = IOException("gRPC transport unavailable: UNAVAILABLE: Failed to connect to gateway")
        coEvery { Client.create(any(), any()) } throws networkException

        engine.state.test {
            assertEquals(XmtpEngineState.Idle, awaitItem())

            engine.initialise(realSigner)
            assertEquals(XmtpEngineState.Loading, awaitItem())

            testScheduler.advanceUntilIdle()

            val errorState = awaitItem()
            assertTrue("State must be Error", errorState is XmtpEngineState.Error)
            val error = (errorState as XmtpEngineState.Error).error
            assertTrue("Error must be NetworkFailure", error is XmtpInitError.NetworkFailure)
            assertEquals(networkException, error.cause)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Verifies that Keystore failure maps to [XmtpInitError.DatabaseError].
     */
    @Test
    fun test_databaseFailure_transitionsToDatabaseError() = runTest(testDispatcher) {
        val keystoreEx = IllegalStateException("Keystore down")
        every { mockKeystoreManager.getOrCreateDatabaseKey() } returns KeystoreResult.Failure.KeystoreError(
            message = "Keystore envelope decrypt failure",
            cause = keystoreEx
        )

        engine.state.test {
            assertEquals(XmtpEngineState.Idle, awaitItem())

            engine.initialise(realSigner)
            assertEquals(XmtpEngineState.Loading, awaitItem())

            testScheduler.advanceUntilIdle()

            val errorState = awaitItem()
            assertTrue("State must be Error", errorState is XmtpEngineState.Error)
            val error = (errorState as XmtpEngineState.Error).error
            assertTrue("Error must be DatabaseError", error is XmtpInitError.DatabaseError)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Verifies that UniFFI InternalException (native Rust unwind panic) is caught and mapped cleanly.
     */
    @Test
    fun test_rustPanic_transitionsToDatabaseError() = runTest(testDispatcher) {
        val rustPanic = InternalException("panicked at 'MLS database schema mismatch v4 != v5'")
        coEvery { Client.create(any(), any()) } throws rustPanic

        engine.state.test {
            assertEquals(XmtpEngineState.Idle, awaitItem())

            engine.initialise(realSigner)
            assertEquals(XmtpEngineState.Loading, awaitItem())

            testScheduler.advanceUntilIdle()

            val errorState = awaitItem()
            assertTrue("State must be Error", errorState is XmtpEngineState.Error)
            val error = (errorState as XmtpEngineState.Error).error
            assertTrue("Error must be DatabaseError on schema mismatch", error is XmtpInitError.DatabaseError)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
