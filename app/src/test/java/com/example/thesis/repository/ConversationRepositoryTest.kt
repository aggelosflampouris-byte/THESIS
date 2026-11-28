package com.example.thesis.repository

import android.util.Log
import app.cash.turbine.test
import com.example.thesis.identity.InMemoryNicknameStorage
import com.example.thesis.notifications.InMemorySeenMessageStorage
import com.example.thesis.xmtp.CallSignal
import com.example.thesis.xmtp.CallSignalType
import com.example.thesis.xmtp.ContentTypeCallSignal
import com.example.thesis.xmtp.ContentTypeProfileNickname
import com.example.thesis.xmtp.ProfileNickname
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.xmtp.android.library.ConsentState
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.Conversations
import org.xmtp.android.library.Dm
import org.xmtp.android.library.Group
import org.xmtp.android.library.SendOptions
import org.xmtp.android.library.codecs.ContentTypeGroupUpdated
import org.xmtp.android.library.codecs.ContentTypeReadReceipt
import org.xmtp.android.library.codecs.ContentTypeText
import org.xmtp.android.library.codecs.ReadReceipt
import org.xmtp.android.library.libxmtp.DecodedMessage
import org.xmtp.android.library.libxmtp.GroupMembershipResult
import org.xmtp.android.library.libxmtp.GroupPermissionPreconfiguration
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.Member
import org.xmtp.android.library.libxmtp.PermissionLevel
import org.xmtp.android.library.libxmtp.PublicIdentity
import org.xmtp.proto.mls.message.contents.TranscriptMessages.GroupUpdated
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
        coEvery { mockConversations.list() } returns emptyList()
        coEvery { mockConversations.list(consentStates = any()) } returns emptyList()

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
        coEvery { mockConversations.list(consentStates = any()) } returns listOf(mockConv1)
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

        coVerify(atLeast = 1) { mockConversations.list(consentStates = any()) }
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

        coEvery { mockConversation.prepareMessage(messagePayload) } returns expectedPublishedId
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
    fun test_createConversation_validInboxId_returnsSuccess() = runTest(testDispatcher) {
        val testInboxId = "072e41b1e11645b3745b4bcaf502f97b34a1b41237edb2b6705596e29875d377"
        val mockDm = mockk<Dm>(relaxed = true)
        coEvery { mockConversations.findOrCreateDm(testInboxId) } returns mockDm
        every { mockClient.inboxId } returns "own_inbox_different_1234567890abcdef1234567890abcdef1234567890abcdef"

        val result = repository.createConversation("@" + testInboxId)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is Conversation.Dm)
        coVerify(exactly = 1) { mockConversations.findOrCreateDm(testInboxId) }
    }

    @Test
    fun test_createConversation_ownInboxId_failsClosed() = runTest(testDispatcher) {
        val ownInboxId = "072e41b1e11645b3745b4bcaf502f97b34a1b41237edb2b6705596e29875d377"
        every { mockClient.inboxId } returns ownInboxId

        val result = repository.createConversation(ownInboxId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { mockConversations.findOrCreateDm(any()) }
    }

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
        coEvery { mockConversation.prepareMessage("Hello XMTP MLS") } returns publishedMessageId
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
        coEvery { mockConversation.prepareMessage("Failing Message") } returns "opt_fail_1"
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

    @Test
    fun test_observeMessages_filtersOutGroupUpdatedSystemMessages() = runTest(testDispatcher) {
        val mockGroupInit = mockk<DecodedMessage>(relaxed = true)
        every { mockGroupInit.id } returns "sys_group_init_1"
        every { mockGroupInit.conversationId } returns testConvId
        every { mockGroupInit.senderInboxId } returns testPeerAddress
        every { mockGroupInit.encodedContent.type } returns ContentTypeGroupUpdated
        every { mockGroupInit.body } returns "a".repeat(64)
        every { mockGroupInit.sentAt } returns Date(500L)
        every { mockGroupInit.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        val mockUserMsg = mockk<DecodedMessage>(relaxed = true)
        every { mockUserMsg.id } returns "user_msg_1"
        every { mockUserMsg.conversationId } returns testConvId
        every { mockUserMsg.senderInboxId } returns testPeerAddress
        every { mockUserMsg.encodedContent.type } returns ContentTypeText
        every { mockUserMsg.content<Any>() } returns "Hello MLS"
        every { mockUserMsg.body } returns "Hello MLS"
        every { mockUserMsg.sentAt } returns Date(1000L)
        every { mockUserMsg.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        val mockStreamGroupUpdate = mockk<DecodedMessage>(relaxed = true)
        every { mockStreamGroupUpdate.id } returns "sys_group_update_2"
        every { mockStreamGroupUpdate.conversationId } returns testConvId
        every { mockStreamGroupUpdate.senderInboxId } returns testPeerAddress
        every { mockStreamGroupUpdate.encodedContent.type } returns ContentTypeGroupUpdated
        every { mockStreamGroupUpdate.body } returns "b".repeat(64)
        every { mockStreamGroupUpdate.sentAt } returns Date(1500L)
        every { mockStreamGroupUpdate.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        val mockStreamUserMsg = mockk<DecodedMessage>(relaxed = true)
        every { mockStreamUserMsg.id } returns "user_msg_2"
        every { mockStreamUserMsg.conversationId } returns testConvId
        every { mockStreamUserMsg.senderInboxId } returns testPeerAddress
        every { mockStreamUserMsg.encodedContent.type } returns ContentTypeText
        every { mockStreamUserMsg.content<Any>() } returns "Real reply"
        every { mockStreamUserMsg.body } returns "Real reply"
        every { mockStreamUserMsg.sentAt } returns Date(2000L)
        every { mockStreamUserMsg.deliveryStatus } returns DecodedMessage.MessageDeliveryStatus.PUBLISHED

        val streamFlow = MutableSharedFlow<DecodedMessage>()
        coEvery { mockConversation.messages() } returns listOf(mockGroupInit, mockUserMsg)
        every { mockConversation.streamMessages() } returns streamFlow

        repository.observeMessages(mockConversation).test {
            val initial = awaitItem()
            assertEquals(1, initial.size)
            assertEquals("user_msg_1", initial[0].id)
            assertEquals("Hello MLS", initial[0].body)

            streamFlow.emit(mockStreamGroupUpdate)
            testScheduler.advanceUntilIdle()
            expectNoEvents()

            streamFlow.emit(mockStreamUserMsg)
            testScheduler.advanceUntilIdle()
            val updated = awaitItem()
            assertEquals(2, updated.size)
            assertEquals("user_msg_2", updated[1].id)
            assertEquals("Real reply", updated[1].body)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun test_observeMessages_filtersOutDecodedGroupUpdatedAndBlankMessages() {
        val mockDecodedGroup = mockk<DecodedMessage>(relaxed = true)
        every { mockDecodedGroup.encodedContent.type } returns ContentTypeText
        every { mockDecodedGroup.content<Any>() } returns GroupUpdated.getDefaultInstance()
        every { mockDecodedGroup.body } returns "some fallback text"
        assertFalse(mockDecodedGroup.isDisplayableUserMessage())

        val mockBlank = mockk<DecodedMessage>(relaxed = true)
        every { mockBlank.encodedContent.type } returns ContentTypeText
        every { mockBlank.content<Any>() } returns "   "
        every { mockBlank.body } returns "   "
        assertFalse(mockBlank.isDisplayableUserMessage())

        val mockValid = mockk<DecodedMessage>(relaxed = true)
        every { mockValid.encodedContent.type } returns ContentTypeText
        every { mockValid.content<Any>() } returns "Valid MLS message"
        every { mockValid.body } returns "Valid MLS message"
        assertTrue(mockValid.isDisplayableUserMessage())
    }

    @Test
    fun test_streamAllMessages_filtersSystemMessagesAndTargetsSpecificConversation() = runTest(testDispatcher) {
        val allMessagesStream = MutableSharedFlow<DecodedMessage>()
        val perConvStream = MutableSharedFlow<DecodedMessage>()
        every { mockConversations.streamAllMessages() } returns allMessagesStream
        every { mockConversation.streamMessages() } returns perConvStream

        val targetUserMsg = mockk<DecodedMessage>(relaxed = true)
        every { targetUserMsg.id } returns "target_user_msg_1"
        every { targetUserMsg.conversationId } returns testConvId
        every { targetUserMsg.senderInboxId } returns testPeerAddress
        every { targetUserMsg.encodedContent.type } returns ContentTypeText
        every { targetUserMsg.content<Any>() } returns "Targeted update"
        every { targetUserMsg.body } returns "Targeted update"
        every { targetUserMsg.sentAt } returns Date(5000L)

        val sysMsg = mockk<DecodedMessage>(relaxed = true)
        every { sysMsg.id } returns "sys_ignore_1"
        every { sysMsg.conversationId } returns testConvId
        every { sysMsg.encodedContent.type } returns ContentTypeGroupUpdated
        every { sysMsg.body } returns testPeerAddress

        val otherUserMsg = mockk<DecodedMessage>(relaxed = true)
        every { otherUserMsg.id } returns "other_msg_1"
        every { otherUserMsg.conversationId } returns "other_conv_999"
        every { otherUserMsg.encodedContent.type } returns ContentTypeText
        every { otherUserMsg.content<Any>() } returns "Other chat"
        every { otherUserMsg.body } returns "Other chat"

        coEvery { mockConversation.messages() } returns emptyList()

        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val polledRepo = ConversationRepository(
            engine = mockEngine,
            repositoryScope = repoScope,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 5_000L
        )
        try {
            polledRepo.observeMessages(mockConversation).test {
                val initial = awaitItem()
                assertTrue(initial.isEmpty())
                coVerify(exactly = 1) { mockConversation.sync() }

                // System transcript on streamAllMessages is ignored
                allMessagesStream.emit(sysMsg)
                testScheduler.runCurrent()
                coVerify(exactly = 1) { mockConversation.sync() }

                // User message for another conversation does not trigger wildcard sync on mockConversation
                allMessagesStream.emit(otherUserMsg)
                testScheduler.runCurrent()
                coVerify(exactly = 1) { mockConversation.sync() }

                // Valid user message for testConvId triggers targeted sync and emits update
                coEvery { mockConversation.messages() } returns listOf(targetUserMsg)
                allMessagesStream.emit(targetUserMsg)
                testScheduler.runCurrent()
                coVerify(exactly = 2) { mockConversation.sync() }

                val updated = awaitItem()
                assertEquals(1, updated.size)
                assertEquals("target_user_msg_1", updated[0].id)

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repoScope.cancel()
            polledRepo.close()
        }
    }

    @Test
    fun test_close_cancelsInternalRepositoryScopeWithoutCancellingParent() = runTest(testDispatcher) {
        val allMessagesStream = MutableSharedFlow<DecodedMessage>()
        every { mockConversations.streamAllMessages() } returns allMessagesStream

        val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repo = ConversationRepository(
            engine = mockEngine,
            repositoryScope = parentScope,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 5_000L
        )

        testScheduler.runCurrent()
        assertEquals(1, allMessagesStream.subscriptionCount.value)

        repo.close()
        testScheduler.runCurrent()

        assertEquals(
            "Closing repository must cancel internal stream collectors",
            0,
            allMessagesStream.subscriptionCount.value
        )
        assertTrue(
            "Closing repository must not cancel caller parent scope",
            parentScope.coroutineContext[Job]?.isActive == true
        )
        parentScope.cancel()
    }

    @Test
    fun test_incomingMessages_emitsDisplayableUserMessagesAndSkipsGroupUpdated() = runTest(testDispatcher) {
        val allMessagesStream = MutableSharedFlow<DecodedMessage>()
        every { mockConversations.streamAllMessages() } returns allMessagesStream

        val sysMsg = mockk<DecodedMessage>(relaxed = true)
        every { sysMsg.id } returns "sys_notif_skip"
        every { sysMsg.conversationId } returns testConvId
        every { sysMsg.encodedContent.type } returns ContentTypeGroupUpdated
        every { sysMsg.body } returns testPeerAddress

        val userMsg = mockk<DecodedMessage>(relaxed = true)
        every { userMsg.id } returns "user_notif_1"
        every { userMsg.conversationId } returns testConvId
        every { userMsg.senderInboxId } returns testPeerAddress
        every { userMsg.encodedContent.type } returns ContentTypeText
        every { userMsg.content<Any>() } returns "Priority ping!"
        every { userMsg.body } returns "Priority ping!"
        every { userMsg.sentAt } returns Date(9000L)

        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repo = ConversationRepository(
            engine = mockEngine,
            repositoryScope = repoScope,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 5_000L
        )
        try {
            repo.incomingMessages.test {
                testScheduler.runCurrent()

                allMessagesStream.emit(sysMsg)
                allMessagesStream.emit(userMsg)
                testScheduler.runCurrent()

                val emitted = awaitItem()
                assertEquals("user_notif_1", emitted.id)
                assertEquals("Priority ping!", emitted.body)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repoScope.cancel()
            repo.close()
        }
    }

    @Test
    fun test_pollIncomingMessages_emitsNewMessagesInExistingConversationAfterBaseline() = runTest(testDispatcher) {
        val allMessagesStream = MutableSharedFlow<DecodedMessage>()
        every { mockConversations.streamAllMessages() } returns allMessagesStream

        val oldMsg = mockk<DecodedMessage>(relaxed = true)
        every { oldMsg.id } returns "hist_msg_1"
        every { oldMsg.conversationId } returns testConvId
        every { oldMsg.senderInboxId } returns testPeerAddress
        every { oldMsg.encodedContent.type } returns ContentTypeText
        every { oldMsg.content<Any>() } returns "Old message"
        every { oldMsg.body } returns "Old message"
        every { oldMsg.sentAt } returns Date(1000L)

        val newMsg = mockk<DecodedMessage>(relaxed = true)
        every { newMsg.id } returns "live_bg_msg_2"
        every { newMsg.conversationId } returns testConvId
        every { newMsg.senderInboxId } returns testPeerAddress
        every { newMsg.encodedContent.type } returns ContentTypeText
        every { newMsg.content<Any>() } returns "Background message"
        every { newMsg.body } returns "Background message"
        every { newMsg.sentAt } returns Date(2000L)

        coEvery { mockConversations.list(consentStates = any()) } returns listOf(mockConversation)
        coEvery { mockConversation.messages() } returns listOf(oldMsg)

        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repo = ConversationRepository(
            engine = mockEngine,
            repositoryScope = repoScope,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 5_000L
        )
        try {
            repo.incomingMessages.test {
                val seedCount = repo.pollIncomingMessages(seedOnly = true).getOrThrow()
                assertEquals(0, seedCount)
                expectNoEvents()

                coEvery { mockConversation.messages() } returns listOf(oldMsg, newMsg)
                val pulledCount = repo.pollIncomingMessages(seedOnly = false).getOrThrow()
                assertEquals(1, pulledCount)

                val emitted = awaitItem()
                assertEquals("live_bg_msg_2", emitted.id)
                assertEquals("Background message", emitted.body)

                val dupCount = repo.pollIncomingMessages(seedOnly = false).getOrThrow()
                assertEquals(0, dupCount)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repoScope.cancel()
            repo.close()
        }
    }

    @Test
    fun test_pollIncomingMessages_persistsSeenIdsAcrossColdRestartWithoutReNotifyingOrSwallowingNew() = runTest(testDispatcher) {
        val allMessagesStream = MutableSharedFlow<DecodedMessage>()
        every { mockConversations.streamAllMessages() } returns allMessagesStream

        val oldMsg = mockk<DecodedMessage>(relaxed = true)
        every { oldMsg.id } returns "old_msg_1"
        every { oldMsg.conversationId } returns testConvId
        every { oldMsg.senderInboxId } returns testPeerAddress
        every { oldMsg.encodedContent.type } returns ContentTypeText
        every { oldMsg.content<Any>() } returns "Already seen"
        every { oldMsg.body } returns "Already seen"
        every { oldMsg.sentAt } returns Date(1000L)

        val missedMsg = mockk<DecodedMessage>(relaxed = true)
        every { missedMsg.id } returns "missed_bg_msg_2"
        every { missedMsg.conversationId } returns testConvId
        every { missedMsg.senderInboxId } returns testPeerAddress
        every { missedMsg.encodedContent.type } returns ContentTypeText
        every { missedMsg.content<Any>() } returns "Missed while process killed"
        every { missedMsg.body } returns "Missed while process killed"
        every { missedMsg.sentAt } returns Date(2000L)

        val sharedStorage = InMemorySeenMessageStorage()
        coEvery { mockConversations.list(consentStates = any()) } returns listOf(mockConversation)
        coEvery { mockConversation.messages() } returns listOf(oldMsg)

        val scope1 = CoroutineScope(SupervisorJob() + testDispatcher)
        val repo1 = ConversationRepository(
            engine = mockEngine,
            repositoryScope = scope1,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 5_000L,
            seenMessageStorage = sharedStorage
        )
        repo1.pollIncomingMessagesAndCollect(seedOnly = true).getOrThrow()
        repo1.close()
        scope1.cancel()

        assertTrue(sharedStorage.isBaselineEstablished())
        assertEquals(listOf("old_msg_1"), sharedStorage.loadSeenIds())

        coEvery { mockConversation.messages() } returns listOf(oldMsg, missedMsg)
        val scope2 = CoroutineScope(SupervisorJob() + testDispatcher)
        val repo2 = ConversationRepository(
            engine = mockEngine,
            repositoryScope = scope2,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 5_000L,
            seenMessageStorage = sharedStorage
        )
        try {
            repo2.incomingMessages.test {
                val batch = repo2.pollIncomingMessagesAndCollect(seedOnly = false).getOrThrow()
                assertEquals(listOf("missed_bg_msg_2"), batch.newMessages.map { it.id })

                val emitted = awaitItem()
                assertEquals("missed_bg_msg_2", emitted.id)
                expectNoEvents()

                repo2.resetNotificationBaseline()
                assertFalse(sharedStorage.isBaselineEstablished())
                assertTrue(sharedStorage.loadSeenIds().isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            scope2.cancel()
            repo2.close()
        }
    }

    @Test
    fun test_readReceiptAndNicknameControlFrames_updateStateWithoutPollutingChatOrNotifications() = runTest(testDispatcher) {
        every { mockClient.inboxId } returns testSenderAddress
        val streamFlow = MutableSharedFlow<DecodedMessage>()
        every { mockConversation.streamMessages() } returns streamFlow
        coEvery { mockConversation.messages() } returns emptyList()

        val peerReceiptMsg = mockk<DecodedMessage>(relaxed = true)
        every { peerReceiptMsg.id } returns "ctrl_rcpt_1"
        every { peerReceiptMsg.conversationId } returns testConvId
        every { peerReceiptMsg.senderInboxId } returns testPeerAddress
        every { peerReceiptMsg.encodedContent.type } returns ContentTypeReadReceipt
        every { peerReceiptMsg.content<Any>() } returns ReadReceipt
        every { peerReceiptMsg.sentAt } returns Date(4_500L)
        every { peerReceiptMsg.body } returns ""

        val peerNickMsg = mockk<DecodedMessage>(relaxed = true)
        every { peerNickMsg.id } returns "ctrl_nick_1"
        every { peerNickMsg.conversationId } returns testConvId
        every { peerNickMsg.senderInboxId } returns testPeerAddress
        every { peerNickMsg.encodedContent.type } returns ContentTypeProfileNickname
        every { peerNickMsg.content<Any>() } returns ProfileNickname("Alice MLS")
        every { peerNickMsg.sentAt } returns Date(4_600L)
        every { peerNickMsg.body } returns ""

        val nicknameStore = InMemoryNicknameStorage()
        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repo = ConversationRepository(
            engine = mockEngine,
            repositoryScope = repoScope,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 0L,
            nicknameStorage = nicknameStore
        )

        try {
            repo.incomingMessages.test {
                repo.observeMessages(mockConversation).test {
                    val initial = awaitItem()
                    assertTrue(initial.isEmpty())

                    streamFlow.emit(peerReceiptMsg)
                    streamFlow.emit(peerNickMsg)
                    testScheduler.runCurrent()

                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(4_500L, repo.peerReadTimestamps.value[testConvId])
            assertEquals("Alice MLS", nicknameStore.snapshot.value.sharedNicknames[testPeerAddress])
        } finally {
            repoScope.cancel()
            repo.close()
        }
    }

    @Test
    fun test_sendReadReceiptAndSendProfileNickname_dispatchEncryptedPayloads() = runTest(testDispatcher) {
        coEvery { mockConversation.send(any<Any>(), any<SendOptions>()) } returns "ctrl_sent_id"

        val receiptResult = repository.sendReadReceipt(mockConversation)
        assertTrue(receiptResult.isSuccess)
        assertEquals("ctrl_sent_id", receiptResult.getOrNull())
        assertTrue((repository.localReadTimestamps.value[testConvId] ?: 0L) > 0L)

        val nickResult = repository.sendProfileNickname(mockConversation, "  Μαρία  ")
        assertTrue(nickResult.isSuccess)
        assertEquals("ctrl_sent_id", nickResult.getOrNull())

        val blankResult = repository.sendProfileNickname(mockConversation, "   ")
        assertTrue(blankResult.isFailure)

        coVerify(exactly = 1) {
            mockConversation.send(
                match<Any> { it is ReadReceipt },
                match<SendOptions> { it.contentType == ContentTypeReadReceipt }
            )
        }
        coVerify(exactly = 1) {
            mockConversation.send(
                match<Any> { it is ProfileNickname && it.nickname == "Μαρία" },
                match<SendOptions> { it.contentType == ContentTypeProfileNickname }
            )
        }
    }

    @Test
    fun test_callSignalControlFrames_routedToIncomingCallSignalsWithoutPollutingChatOrTextNotifications() = runTest(testDispatcher) {
        every { mockClient.inboxId } returns testSenderAddress
        val streamFlow = MutableSharedFlow<DecodedMessage>()
        every { mockConversation.streamMessages() } returns streamFlow
        coEvery { mockConversation.messages() } returns emptyList()

        val offerSignal = CallSignal(
            callId = "call-uuid-101",
            type = CallSignalType.OFFER,
            timestampMs = 5_000L,
            sdp = "v=0\r\na=fingerprint:sha-256 aa:bb:cc"
        )
        val callMsg = mockk<DecodedMessage>(relaxed = true)
        every { callMsg.id } returns "call_sig_1"
        every { callMsg.conversationId } returns testConvId
        every { callMsg.senderInboxId } returns testPeerAddress
        every { callMsg.encodedContent.type } returns ContentTypeCallSignal
        every { callMsg.content<Any>() } returns offerSignal
        every { callMsg.sentAt } returns Date(5_000L)
        every { callMsg.body } returns ""

        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repo = ConversationRepository(
            engine = mockEngine,
            repositoryScope = repoScope,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 0L
        )
        try {
            repo.incomingCallSignals.test {
                repo.incomingMessages.test {
                    repo.observeMessages(mockConversation).test {
                        val initial = awaitItem()
                        assertTrue(initial.isEmpty())

                        streamFlow.emit(callMsg)
                        testScheduler.runCurrent()

                        expectNoEvents()
                        cancelAndIgnoreRemainingEvents()
                    }
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
                val emittedCall = awaitItem()
                assertEquals("call_sig_1", emittedCall.messageId)
                assertEquals(testConvId, emittedCall.conversationId)
                assertEquals(testPeerAddress, emittedCall.senderInboxId)
                assertEquals(offerSignal, emittedCall.signal)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repoScope.cancel()
            repo.close()
        }
    }

    @Test
    fun test_callSignalDeduplication_preventsDuplicateEmissionsAcrossStreams() = runTest(testDispatcher) {
        every { mockClient.inboxId } returns testSenderAddress
        val allStream = MutableSharedFlow<DecodedMessage>()
        val convStream = MutableSharedFlow<DecodedMessage>()
        every { mockConversations.streamAllMessages() } returns allStream
        every { mockConversation.streamMessages() } returns convStream
        coEvery { mockConversation.messages() } returns emptyList()

        val hangupSignal = CallSignal(
            callId = "call-dup-202",
            type = CallSignalType.HANGUP,
            timestampMs = 7_000L,
            reason = "hangup"
        )
        val callMsg = mockk<DecodedMessage>(relaxed = true)
        every { callMsg.id } returns "call_sig_dup_1"
        every { callMsg.conversationId } returns testConvId
        every { callMsg.senderInboxId } returns testPeerAddress
        every { callMsg.encodedContent.type } returns ContentTypeCallSignal
        every { callMsg.content<Any>() } returns hangupSignal
        every { callMsg.sentAt } returns Date(7_000L)

        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repo = ConversationRepository(
            engine = mockEngine,
            repositoryScope = repoScope,
            ioDispatcher = testDispatcher,
            syncIntervalMs = 5_000L
        )
        try {
            repo.incomingCallSignals.test {
                repo.observeMessages(mockConversation).test {
                    awaitItem()
                    testScheduler.runCurrent()

                    allStream.emit(callMsg)
                    convStream.emit(callMsg)
                    testScheduler.runCurrent()

                    cancelAndIgnoreRemainingEvents()
                }
                val first = awaitItem()
                assertEquals("call_sig_dup_1", first.messageId)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repoScope.cancel()
            repo.close()
        }
    }

    @Test
    fun test_sendCallSignal_dispatchesWithContentTypeCallSignal() = runTest(testDispatcher) {
        coEvery { mockConversation.send(any<Any>(), any<SendOptions>()) } returns "call_msg_sent_1"

        val signal = CallSignal(
            callId = "call-out-303",
            type = CallSignalType.OFFER,
            timestampMs = 8_000L,
            sdp = "v=0"
        )
        val result = repository.sendCallSignal(testConvId, signal)
        assertTrue(result.isSuccess)
        assertEquals("call_msg_sent_1", result.getOrNull())

        coVerify(exactly = 1) {
            mockConversation.send(
                match<Any> { it is CallSignal && it.callId == "call-out-303" },
                match<SendOptions> { it.contentType == ContentTypeCallSignal }
            )
        }
    }

    @Test
    fun test_createGroupConversation_successWithMixedIdentifiers() = runTest(testDispatcher) {
        val localInbox = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val inboxPeer = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val ethResolvedInbox = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        every { mockClient.inboxId } returns localInbox
        every { mockClient.environment } returns org.xmtp.android.library.XMTPEnvironment.PRODUCTION
        coEvery { mockClient.canMessage(any()) } returns mapOf(testPeerAddress to true)
        coEvery { mockClient.inboxIdFromIdentity(any()) } returns ethResolvedInbox

        val mockGroup = mockk<Group>(relaxed = true)
        every { mockGroup.id } returns "group_mls_1"
        every { mockGroup.topic } returns "xmtp/mls/group_1"
        coEvery {
            mockConversations.newGroup(
                inboxIds = any(),
                permissions = GroupPermissionPreconfiguration.ADMIN_ONLY,
                groupName = "Thesis Squad",
                groupImageUrlSquare = any(),
                groupDescription = any(),
                disappearingMessageSettings = any(),
                appData = any()
            )
        } returns mockGroup

        val result = repository.createGroupConversation(
            groupName = "  Thesis\nSquad  ",
            rawPeerIdentifiers = listOf(inboxPeer.uppercase(), testPeerAddress.uppercase(), localInbox)
        )

        assertTrue(result.isSuccess)
        val created = result.getOrNull() as Conversation.Group
        assertEquals("group_mls_1", created.id)
        coVerify(exactly = 1) {
            mockConversations.newGroup(
                inboxIds = listOf(inboxPeer, ethResolvedInbox),
                permissions = GroupPermissionPreconfiguration.ADMIN_ONLY,
                groupName = "Thesis Squad",
                groupImageUrlSquare = "",
                groupDescription = "",
                disappearingMessageSettings = null,
                appData = null
            )
        }
        coVerify(exactly = 1) { mockGroup.updateConsentState(ConsentState.ALLOWED) }
        coVerify(exactly = 1) { mockGroup.sync() }
    }

    @Test
    fun test_createGroupConversation_validationFailures() = runTest(testDispatcher) {
        val localInbox = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        every { mockClient.inboxId } returns localInbox
        every { mockClient.environment } returns org.xmtp.android.library.XMTPEnvironment.PRODUCTION

        val blankName = repository.createGroupConversation("   ", listOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        assertTrue(blankName.isFailure)

        val loopbackName = repository.createGroupConversation(GroupManagementPolicy.LOOPBACK_GROUP_NAME, listOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        assertTrue(loopbackName.isFailure)

        val selfOnly = repository.createGroupConversation("Team", listOf(localInbox, testSenderAddress))
        assertTrue(selfOnly.isFailure)

        coEvery { mockClient.canMessage(any()) } returns mapOf(testPeerAddress to false)
        val unregistered = repository.createGroupConversation("Team", listOf(testPeerAddress))
        assertTrue(unregistered.isFailure)
    }

    @Test
    fun test_loadGroupDetails_sortsMembersAndResolvesPermissions() = runTest(testDispatcher) {
        val localInbox = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val memberInbox = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val adminInbox = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        every { mockClient.inboxId } returns localInbox

        val selfMember = mockk<Member>(relaxed = true) {
            every { inboxId } returns localInbox
            every { permissionLevel } returns PermissionLevel.SUPER_ADMIN
            every { identities } returns listOf(mockk<PublicIdentity> {
                every { kind } returns IdentityKind.ETHEREUM
                every { identifier } returns testSenderAddress
            })
        }
        val regularMember = mockk<Member>(relaxed = true) {
            every { inboxId } returns memberInbox
            every { permissionLevel } returns PermissionLevel.MEMBER
            every { identities } returns emptyList()
        }
        val adminMember = mockk<Member>(relaxed = true) {
            every { inboxId } returns adminInbox
            every { permissionLevel } returns PermissionLevel.ADMIN
            every { identities } returns emptyList()
        }

        val mockGroup = mockk<Group>(relaxed = true)
        every { mockGroup.id } returns "group_details_1"
        coEvery { mockGroup.name() } returns "Core Group"
        coEvery { mockGroup.description() } returns ""
        coEvery { mockGroup.isActive() } returns true
        coEvery { mockGroup.isSuperAdmin(localInbox) } returns true
        coEvery { mockGroup.isAdmin(localInbox) } returns true
        coEvery { mockGroup.members() } returns listOf(regularMember, adminMember, selfMember)

        val res = repository.loadGroupDetails(mockGroup)
        assertTrue(res.isSuccess)
        val details = res.getOrThrow()
        assertEquals("Core Group", details.name)
        assertTrue(details.isLocalSuperAdmin)
        assertTrue(details.isLocalAdmin)
        assertEquals(listOf(localInbox, adminInbox, memberInbox), details.members.map { it.inboxId })
        assertEquals(testSenderAddress, details.members.first().ethAddress)
    }

    @Test
    fun test_groupMemberMutationsAndLeaveGroup() = runTest(testDispatcher) {
        val localInbox = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val existingInbox = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val newInbox = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        every { mockClient.inboxId } returns localInbox

        val existingMember = mockk<Member>(relaxed = true) {
            every { inboxId } returns existingInbox
            every { permissionLevel } returns PermissionLevel.MEMBER
            every { identities } returns emptyList()
        }
        val mockGroup = mockk<Group>(relaxed = true)
        every { mockGroup.id } returns "group_mut_1"
        coEvery { mockGroup.name() } returns "Group A"
        coEvery { mockGroup.description() } returns ""
        coEvery { mockGroup.isActive() } returns true
        coEvery { mockGroup.isSuperAdmin(localInbox) } returns true
        coEvery { mockGroup.members() } returns listOf(existingMember)
        coEvery { mockGroup.addMembers(listOf(newInbox)) } returns mockk<GroupMembershipResult>(relaxed = true)

        val dupAdd = repository.addGroupMember(mockGroup, existingInbox)
        assertTrue(dupAdd.isFailure)

        val okAdd = repository.addGroupMember(mockGroup, newInbox)
        assertTrue(okAdd.isSuccess)
        coVerify(exactly = 1) { mockGroup.addMembers(listOf(newInbox)) }

        val okRemove = repository.removeGroupMember(mockGroup, existingInbox)
        assertTrue(okRemove.isSuccess)
        coVerify(exactly = 1) { mockGroup.removeMembers(listOf(existingInbox)) }

        val okRename = repository.updateGroupName(mockGroup, "Renamed Squad")
        assertTrue(okRename.isSuccess)
        coVerify(exactly = 1) { mockGroup.updateName("Renamed Squad") }

        val okLeave = repository.leaveGroup(mockGroup)
        assertTrue(okLeave.isSuccess)
        coVerify(exactly = 1) { mockGroup.leaveGroup() }
        coVerify(exactly = 1) { mockGroup.updateConsentState(ConsentState.DENIED) }
    }
}
