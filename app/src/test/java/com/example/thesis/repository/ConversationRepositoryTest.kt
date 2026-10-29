package com.example.thesis.repository

import android.util.Log
import app.cash.turbine.test
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.xmtp.android.library.Client
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.Conversations
import org.xmtp.android.library.Dm
import org.xmtp.android.library.Group
import org.xmtp.android.library.libxmtp.DecodedMessage
import org.xmtp.android.library.libxmtp.PublicIdentity
import java.io.IOException
import java.util.Date

/**
 * Reactive unit test suite for [ConversationRepository] verifying stream lifecycle,
 * backpressure guarantees, MLS message dispatch, and collector disposal behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockEngine: XmtpEngine
    private lateinit var mockClient: Client
    private lateinit var mockConversations: Conversations
    private lateinit var mockConversation: Conversation
    private lateinit var mockIdentity: PublicIdentity
    private lateinit var stateFlow: MutableStateFlow<XmtpEngineState>

    private val testSenderAddress = "0x71c7656ec7ab88b098defb751b7401b5f6d8976f"
    private val testPeerAddress = "0xab5801a7d398351b8be11c439e05c5b3259aec9b"
    private val testConvId = "conv_mls_12345"

    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockEngine = mockk(relaxed = true)
        mockClient = mockk(relaxed = true)
        mockConversations = mockk(relaxed = true)
        mockConversation = mockk(relaxed = true)
        mockIdentity = mockk(relaxed = true)

        every { mockIdentity.identifier } returns testSenderAddress
        every { mockClient.publicIdentity } returns mockIdentity
        every { mockClient.conversations } returns mockConversations
        every { mockEngine.client } returns mockClient

        stateFlow = MutableStateFlow(XmtpEngineState.Ready(mockClient))
        every { mockEngine.state } returns stateFlow

        every { mockConversation.id } returns testConvId
        coEvery { mockConversations.findConversation(testConvId) } returns mockConversation

        coEvery { mockClient.canMessage(any()) } returns mapOf(testPeerAddress to true)

        repository = ConversationRepository(
            xmtpEngine = mockEngine,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 0L
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    /**
     * Requirement 1:
     * Mock XmtpEngine in Ready state; verify the repository emits the initial conversation
     * list and receives streaming updates using Turbine.
     */
    @Test
    fun test_observeConversations_emitsInitialListAndUpdates() = runTest(testDispatcher) {
        val convStream = MutableSharedFlow<Conversation>()
        val mockConv1 = mockk<Conversation>(relaxed = true)
        val mockConv2 = mockk<Conversation>(relaxed = true)

        val date1 = Date(10_000L)
        val date2 = Date(20_000L)

        every { mockConv1.id } returns "conv_mls_1"
        every { mockConv1.topic } returns "xmtp/0/conv_mls_1/proto"
        every { mockConv1.createdAt } returns date1

        every { mockConv2.id } returns "conv_mls_2"
        every { mockConv2.topic } returns "xmtp/0/conv_mls_2/proto"
        every { mockConv2.createdAt } returns date2

        coEvery { mockConversations.list() } returns listOf(mockConv1)
        every { mockConversations.stream() } returns convStream.asSharedFlow()

        repository.observeConversations().test {
            // 1. Initial list emission
            val initial = awaitItem()
            assertEquals(1, initial.size)
            assertEquals("conv_mls_1", initial[0].id)
            assertEquals("xmtp/0/conv_mls_1/proto", initial[0].topic)
            assertEquals(date1, initial[0].createdAt)

            // 2. Real-time stream emission
            convStream.emit(mockConv2)
            testScheduler.advanceUntilIdle()

            val updated = awaitItem()
            assertEquals(2, updated.size)
            assertEquals("conv_mls_2", updated[0].id)
            assertEquals("conv_mls_1", updated[1].id)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(atLeast = 1) { mockConversations.list() }
    }

    /**
     * Requirement 2:
     * Simulate a continuous stream of incoming MLS messages; verify each message is emitted sequentially.
     */
    @Test
    fun test_observeMessages_emitsIncomingMessagesSequentially() = runTest(testDispatcher) {
        val streamFlow = MutableSharedFlow<DecodedMessage>()
        every { mockConversation.streamMessages() } returns streamFlow
        coEvery { mockConversation.messages() } returns emptyList()

        val mockMsg1 = mockk<DecodedMessage>(relaxed = true)
        val mockMsg2 = mockk<DecodedMessage>(relaxed = true)
        val mockMsg3 = mockk<DecodedMessage>(relaxed = true)

        every { mockMsg1.id } returns "mls_msg_seq_1"
        every { mockMsg1.conversationId } returns testConvId
        every { mockMsg1.senderInboxId } returns testPeerAddress
        every { mockMsg1.body } returns "First Sequential MLS Payload"
        every { mockMsg1.sentAt } returns Date(1_000L)
        every { mockMsg1.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        every { mockMsg2.id } returns "mls_msg_seq_2"
        every { mockMsg2.conversationId } returns testConvId
        every { mockMsg2.senderInboxId } returns testPeerAddress
        every { mockMsg2.body } returns "Second Sequential MLS Payload"
        every { mockMsg2.sentAt } returns Date(2_000L)
        every { mockMsg2.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        every { mockMsg3.id } returns "mls_msg_seq_3"
        every { mockMsg3.conversationId } returns testConvId
        every { mockMsg3.senderInboxId } returns testPeerAddress
        every { mockMsg3.body } returns "Third Sequential MLS Payload"
        every { mockMsg3.sentAt } returns Date(3_000L)
        every { mockMsg3.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        repository.observeMessages(testConvId).test {
            // Sequential message 1
            streamFlow.emit(mockMsg1)
            testScheduler.advanceUntilIdle()
            val received1 = awaitItem()
            assertEquals("mls_msg_seq_1", received1.id)
            assertEquals(testConvId, received1.conversationId)
            assertEquals(testPeerAddress, received1.senderAddress)
            assertEquals("First Sequential MLS Payload", received1.body)
            assertEquals(1_000L, received1.sentAt)
            assertEquals(MessageDeliveryStatus.PUBLISHED, received1.deliveryStatus)

            // Sequential message 2
            streamFlow.emit(mockMsg2)
            testScheduler.advanceUntilIdle()
            val received2 = awaitItem()
            assertEquals("mls_msg_seq_2", received2.id)
            assertEquals(testConvId, received2.conversationId)
            assertEquals(testPeerAddress, received2.senderAddress)
            assertEquals("Second Sequential MLS Payload", received2.body)
            assertEquals(2_000L, received2.sentAt)
            assertEquals(MessageDeliveryStatus.PUBLISHED, received2.deliveryStatus)

            // Sequential message 3
            streamFlow.emit(mockMsg3)
            testScheduler.advanceUntilIdle()
            val received3 = awaitItem()
            assertEquals("mls_msg_seq_3", received3.id)
            assertEquals(testConvId, received3.conversationId)
            assertEquals(testPeerAddress, received3.senderAddress)
            assertEquals("Third Sequential MLS Payload", received3.body)
            assertEquals(3_000L, received3.sentAt)
            assertEquals(MessageDeliveryStatus.PUBLISHED, received3.deliveryStatus)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Requirement 3:
     * Verify sendMessage() delegates to the correct conversation handle and returns Result.success().
     */
    @Test
    fun test_sendMessage_dispatchesContentSuccessfully() = runTest(testDispatcher) {
        val messagePayload = "Encrypted MLS Ratchet Payload Content"
        val expectedPublishedId = "msg_published_ack_101"

        every { mockConversation.prepareMessage(messagePayload) } returns expectedPublishedId
        coEvery { mockConversation.publishMessages() } returns Unit
        coEvery { mockConversation.send(messagePayload) } returns expectedPublishedId

        val result = repository.sendMessage(testConvId, messagePayload)

        assertTrue("sendMessage must return Result.success()", result.isSuccess)
        assertEquals(Unit, result.getOrNull())

        // Verify delegation to the targeted conversation handle
        coVerify(exactly = 1) {
            mockConversation.prepareMessage(messagePayload)
        }
        coVerify(exactly = 1) {
            mockConversation.publishMessages()
        }
    }

    /**
     * Requirement 4:
     * Verify that cancelling the collecting coroutine triggers awaitClose and tears down
     * the underlying subscription.
     */
    @Test
    fun test_streamCancelsWhenCollectorJobCancelled() = runTest(testDispatcher) {
        var streamConnectionClosed = false

        val streamFlow = callbackFlow<DecodedMessage> {
            awaitClose {
                streamConnectionClosed = true
            }
        }
        every { mockConversation.streamMessages() } returns streamFlow
        coEvery { mockConversation.messages() } returns emptyList()

        val collectJob = launch {
            repository.observeMessages(testConvId).collect()
        }
        testScheduler.advanceUntilIdle()

        // Stream remains open while collector is active
        assertFalse("Stream must remain open while collector is active", streamConnectionClosed)

        // Cancel the collector job (simulating Composable leaving composition / onStop / onDestroy)
        collectJob.cancel()

        // Advance time past the 5-second WhileSubscribed timeout to trigger upstream disposal
        testScheduler.advanceTimeBy(6_000L)
        testScheduler.advanceUntilIdle()

        assertTrue(
            "Underlying subscription must trigger awaitClose and close when collector is cancelled",
            streamConnectionClosed
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Additional Boundary & Reliability Tests
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun test_createConversation_validAddress_returnsSuccess() = runTest(testDispatcher) {
        val mockDm = mockk<Dm>(relaxed = true)
        coEvery { mockConversations.findOrCreateDmWithIdentity(any()) } returns mockDm

        val result = repository.createConversation(testPeerAddress)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is Conversation.Dm)
        coVerify(exactly = 1) { mockConversations.findOrCreateDmWithIdentity(match { it.identifier.equals(testPeerAddress, ignoreCase = true) }) }
    }

    @Test
    fun test_createConversation_invalidAddress_failsClosed() = runTest(testDispatcher) {
        val result = repository.createConversation("invalid_eth_address")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { mockConversations.findOrCreateDmWithIdentity(any()) }
    }

    @Test
    fun test_createLoopbackConversation_returnsSuccess() = runTest(testDispatcher) {
        val mockGroup = mockk<Group>(relaxed = true)
        coEvery { mockConversations.newGroup(inboxIds = emptyList(), groupName = any()) } returns mockGroup

        val result = repository.createLoopbackConversation()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is Conversation.Group)
        coVerify(exactly = 1) { mockConversations.newGroup(inboxIds = emptyList(), groupName = any()) }
    }

    @Test
    fun test_createConversation_engineNotReady_returnsFailure() = runTest(testDispatcher) {
        every { mockEngine.client } returns null
        stateFlow.value = XmtpEngineState.Idle

        val result = repository.createConversation(testPeerAddress)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun test_sendMessage_optimisticDispatch_successEmitsPendingThenPublished() = runTest(testDispatcher) {
        val publishedMessageId = "msg_published_999"
        coEvery { mockConversation.send("Hello XMTP MLS") } returns publishedMessageId
        every { mockConversation.prepareMessage("Hello XMTP MLS") } returns publishedMessageId
        coEvery { mockConversation.publishMessages() } returns Unit
        every { mockConversation.streamMessages() } returns flowOf()
        coEvery { mockConversation.messages() } returns emptyList()

        repository.observeMessages(testConvId).test {
            val sendResult = repository.sendMessage(testConvId, "Hello XMTP MLS")
            testScheduler.advanceUntilIdle()

            // 1. Optimistic LOCAL_PENDING message
            val pendingMsg = awaitItem()
            assertEquals(testConvId, pendingMsg.conversationId)
            assertEquals("Hello XMTP MLS", pendingMsg.body)
            assertEquals(MessageDeliveryStatus.LOCAL_PENDING, pendingMsg.deliveryStatus)

            // 2. Transmitted PUBLISHED message
            val publishedMsg = awaitItem()
            assertEquals(testConvId, publishedMsg.conversationId)
            assertEquals(publishedMessageId, publishedMsg.id)
            assertEquals(MessageDeliveryStatus.PUBLISHED, publishedMsg.deliveryStatus)

            assertTrue(sendResult.isSuccess)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun test_sendMessage_blankContent_failsClosed() = runTest(testDispatcher) {
        val result = repository.sendMessage(testConvId, "   ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { mockConversation.send(any<String>()) }
    }

    @Test
    fun test_sendMessage_networkFailure_emitsPendingThenFailed() = runTest(testDispatcher) {
        val networkEx = IOException("gRPC connection reset")
        every { mockConversation.prepareMessage("Failing Message") } returns "opt_fail_1"
        coEvery { mockConversation.publishMessages() } throws networkEx
        coEvery { mockConversation.send("Failing Message") } throws networkEx
        every { mockConversation.streamMessages() } returns flowOf()
        coEvery { mockConversation.messages() } returns emptyList()

        repository.observeMessages(testConvId).test {
            val sendResult = repository.sendMessage(testConvId, "Failing Message")
            testScheduler.advanceUntilIdle()

            // 1. Optimistic LOCAL_PENDING
            val pendingMsg = awaitItem()
            assertEquals(MessageDeliveryStatus.LOCAL_PENDING, pendingMsg.deliveryStatus)

            // 2. Delivery failure FAILED
            val failedMsg = awaitItem()
            assertEquals(MessageDeliveryStatus.FAILED, failedMsg.deliveryStatus)

            assertTrue(sendResult.isFailure)
            assertEquals(networkEx, sendResult.exceptionOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun test_observeMessages_emitsHistoricalAndRealTimeMessages() = runTest(testDispatcher) {
        val mockHistorical = mockk<DecodedMessage>(relaxed = true)
        every { mockHistorical.id } returns "msg_hist_1"
        every { mockHistorical.conversationId } returns testConvId
        every { mockHistorical.senderInboxId } returns testPeerAddress
        every { mockHistorical.body } returns "Old Message"
        every { mockHistorical.sentAt } returns Date(1000L)
        every { mockHistorical.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        val mockIncoming = mockk<DecodedMessage>(relaxed = true)
        every { mockIncoming.id } returns "msg_incoming_2"
        every { mockIncoming.conversationId } returns testConvId
        every { mockIncoming.senderInboxId } returns testPeerAddress
        every { mockIncoming.body } returns "New Realtime Message"
        every { mockIncoming.sentAt } returns Date(2000L)
        every { mockIncoming.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        coEvery { mockConversation.messages() } returns listOf(mockHistorical)
        every { mockConversation.streamMessages() } returns flowOf(mockIncoming)

        repository.observeMessages(testConvId).test {
            // Historical
            val first = awaitItem()
            assertEquals("msg_hist_1", first.id)
            assertEquals("Old Message", first.body)
            assertEquals(MessageDeliveryStatus.PUBLISHED, first.deliveryStatus)

            // Real-time incoming
            val second = awaitItem()
            assertEquals("msg_incoming_2", second.id)
            assertEquals("New Realtime Message", second.body)
            assertEquals(MessageDeliveryStatus.PUBLISHED, second.deliveryStatus)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun test_observeMessages_multicastSharingAcrossMultipleCollectors() = runTest(testDispatcher) {
        val mockIncoming = mockk<DecodedMessage>(relaxed = true)
        every { mockIncoming.id } returns "shared_msg_1"
        every { mockIncoming.conversationId } returns testConvId
        every { mockIncoming.senderInboxId } returns testPeerAddress
        every { mockIncoming.body } returns "Multicast Broadcast"
        every { mockIncoming.sentAt } returns Date(3000L)
        every { mockIncoming.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        val streamFlow = MutableSharedFlow<DecodedMessage>()
        every { mockConversation.streamMessages() } returns streamFlow
        coEvery { mockConversation.messages() } returns emptyList()

        // Collector 1
        repository.observeMessages(testConvId).test {
            // Collector 2
            repository.observeMessages(testConvId).test {
                streamFlow.emit(mockIncoming)
                testScheduler.advanceUntilIdle()

                val msg2 = awaitItem()
                assertEquals("shared_msg_1", msg2.id)
                assertEquals("Multicast Broadcast", msg2.body)
                cancelAndIgnoreRemainingEvents()
            }

            val msg1 = awaitItem()
            assertEquals("shared_msg_1", msg1.id)
            cancelAndIgnoreRemainingEvents()
        }

        // streamMessages must be called strictly once upstream despite two active collectors
        coVerify(exactly = 1) { mockConversation.streamMessages() }
    }
    @Test
    fun test_observeMessages_directConversation_deduplicatesHistoryAndStream() = runTest(testDispatcher) {
        val mockMsg = mockk<DecodedMessage>(relaxed = true)
        every { mockMsg.id } returns "msg_shared_id"
        every { mockMsg.conversationId } returns testConvId
        every { mockMsg.senderInboxId } returns testPeerAddress
        every { mockMsg.body } returns "Duplicate Message Test"

        val streamFlow = MutableSharedFlow<DecodedMessage>()
        coEvery { mockConversation.messages() } returns listOf(mockMsg)
        every { mockConversation.streamMessages() } returns streamFlow

        repository.observeMessages(mockConversation).test {
            // First emission from historical fetch
            val initialList = awaitItem()
            assertEquals(1, initialList.size)
            assertEquals("msg_shared_id", initialList[0].id)

            // Emit duplicate incoming message
            streamFlow.emit(mockMsg)
            testScheduler.advanceUntilIdle()

            // Map dedupes by key and emits updated list containing exactly 1 element
            val secondEmission = awaitItem()
            assertEquals(1, secondEmission.size)
            assertEquals("msg_shared_id", secondEmission[0].id)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
