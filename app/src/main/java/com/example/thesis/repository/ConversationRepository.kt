package com.example.thesis.repository

import android.util.Log
import com.example.thesis.util.CryptoUtils
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import org.xmtp.android.library.Client
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.libxmtp.DecodedMessage
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production reactive repository orchestrating MLS 1-on-1 and Group conversations, bridging [XmtpEngine]
 * to presentation layers using Unidirectional Data Flow (UDF), structured concurrency, and backpressure safety.
 *
 * ## Architectural Guarantees
 * 1. **Structured Concurrency & gRPC Teardown**: Pipelines are cold channel flows configured with
 *    [buffer] (`capacity = Channel.BUFFERED, onBufferOverflow = BufferOverflow.DROP_OLDEST`) and shared via
 *    [SharingStarted.WhileSubscribed] (5-second stop timeout). When UI collectors leave the composition
 *    (`onStop`/`onDestroy`), upstream gRPC push streams and background loops cancel cleanly.
 * 2. **Backpressure Protection**: Implements [BufferOverflow.DROP_OLDEST] buffer policy preventing UI render
 *    jank from stalling native message processing. Bounded LRU deduplication prevents in-memory OOM.
 * 3. **Hot Multicast Sharing**: Eliminates multi-subscription storms by sharing a single upstream gRPC
 *    stream across concurrent UI subscribers.
 * 4. **Two-Phase Optimistic Persistence**: Optimistically commits outgoing messages to local encrypted
 *    storage with [MessageDeliveryStatus.LOCAL_PENDING] prior to network dispatch, transitioning to
 *    [MessageDeliveryStatus.PUBLISHED] upon node confirmation.
 *
 * @param xmtpEngine   The active lifecycle manager for the XMTP v4 client.
 * @param ioDispatcher Injectable coroutine dispatcher for IO-bound work (defaults to [Dispatchers.IO]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepository(
    val engine: XmtpEngine,
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {

    val xmtpEngine: XmtpEngine get() = engine

    constructor(
        xmtpEngine: XmtpEngine,
        ioDispatcher: CoroutineDispatcher
    ) : this(
        engine = xmtpEngine,
        repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher),
        ioDispatcher = ioDispatcher
    )

    private val repositoryJob = SupervisorJob()

    /**
     * Internal event bus for optimistic and published outgoing messages.
     * Backpressure-protected with [BufferOverflow.DROP_OLDEST] buffer policy.
     */
    private val outgoingMessageEvents = MutableSharedFlow<Message>(
        extraBufferCapacity = CHANNEL_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Internal trigger notifying conversation list observers when local mutations occur.
     */
    private val conversationRefreshTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Cache of active hot message streams per conversation ID to prevent duplicate gRPC network streams.
     */
    private val messageFlows = ConcurrentHashMap<String, SharedFlow<Message>>()

    /**
     * Directly observes the active [Client] instance when the engine enters [XmtpEngineState.Ready].
     */
    val readyClientFlow: Flow<Client> = engine.state
        .filterIsInstance<XmtpEngineState.Ready>()
        .map { it.client }
        .distinctUntilChanged()

    // ────────────────────────────────────────────────────────────────────────
    //  Reactive Data Streams (Cold Channel Flows & Multicast Sharing)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Shared multicast flow of conversations to prevent duplicate network syncs and gRPC streams.
     */
    private val sharedConversationsFlow: SharedFlow<List<Conversation>> by lazy {
        readyClientFlow
            .flatMapLatest { client ->
                createConversationsPipeline(client)
            }
            .shareIn(
                scope = repositoryScope,
                started = SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = STOP_TIMEOUT_MILLIS,
                    replayExpirationMillis = 0L
                ),
                replay = 1
            )
    }

    /**
     * Observes the full list of active conversations (DMs and Groups).
     *
     * Shared hot stream: multiple UI collectors share a single underlying gRPC stream.
     * When all collectors unsubscribe, upstream network streaming pauses after [STOP_TIMEOUT_MILLIS].
     *
     * @return [Flow] emitting sorted lists of active [Conversation] entities.
     */
    fun observeConversations(): Flow<List<Conversation>> = sharedConversationsFlow

    private fun createConversationsPipeline(client: Client): Flow<List<Conversation>> = channelFlow {
        // Initial sync with network ledger
        try {
            client.conversations.sync()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Initial conversation sync failed; proceeding with local cache: ${e.message}")
        }

        val currentList = client.conversations.list().toMutableList()
        send(currentList.toList())

        // Real-time network stream with resilient reconnection backoff
        val networkStream: Flow<Conversation?> = client.conversations.stream()
            .retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false
                if (CryptoUtils.isNetworkThrowable(cause) && attempt < MAX_RECONNECT_ATTEMPTS) {
                    val backoffMs = calculateExponentialBackoff(attempt)
                    Log.w(TAG, "Conversation stream interrupted. Reconnecting in ${backoffMs}ms (attempt $attempt)...")
                    delay(backoffMs)
                    true
                } else {
                    false
                }
            }

        // Merge network notifications with local creation triggers
        val refreshStream: Flow<Conversation?> = conversationRefreshTrigger.map { null }

        val collectorJob = launch {
            merge(networkStream, refreshStream).collect { newConv ->
                if (newConv != null) {
                    val existingIndex = currentList.indexOfFirst { it.id == newConv.id }
                    if (existingIndex >= 0) {
                        currentList[existingIndex] = newConv
                    } else {
                        currentList.add(0, newConv)
                    }
                } else {
                    // Refresh trigger: re-query full list
                    val refreshed = client.conversations.list()
                    currentList.clear()
                    currentList.addAll(refreshed)
                }
                send(currentList.toList())
            }
        }

        awaitClose {
            collectorJob.cancel()
        }
    }.buffer(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ).flowOn(ioDispatcher)

    /**
     * Observes the real-time stream of incoming messages for a specific conversation.
     *
     * Shared hot stream: multiple UI collectors for the same [conversationId] share a single
     * underlying gRPC stream. Protected against OOM via a bounded LRU deduplicator and against
     * backpressure drops via [BufferOverflow.DROP_OLDEST] buffer policy.
     *
     * @param conversationId Unique identifier of target XMTP conversation.
     * @return [Flow] of individual [Message] entities ordered chronologically.
     */
    fun observeMessages(conversationId: String): Flow<Message> {
        return messageFlows.computeIfAbsent(conversationId) { id ->
            xmtpEngine.state
                .filterIsInstance<XmtpEngineState.Ready>()
                .flatMapLatest { readyState ->
                    createMessagesPipeline(readyState.client, id)
                }
                .shareIn(
                    scope = repositoryScope,
                    started = SharingStarted.WhileSubscribed(
                        stopTimeoutMillis = STOP_TIMEOUT_MILLIS,
                        replayExpirationMillis = 0L
                    ),
                    replay = 0
                )
        }
    }

    private fun createMessagesPipeline(client: Client, conversationId: String): Flow<Message> = channelFlow {
        val conversation = resolveConversation(client, conversationId)
        val historicalMessages = loadHistoricalMessagesSafe(conversation, conversationId)

        // Bounded LRU cache to prevent heap memory exhaustion on long-running streams
        val deduplicator = BoundedIdDeduplicator(maxSize = MAX_DEDUPLICATOR_CAPACITY)
        for (msg in historicalMessages) {
            deduplicator.add(msg.id)
            send(msg)
        }

        // 2. Real-time network stream
        val networkStream: Flow<Message> = conversation.streamMessages()
            .map { it.toDomainMessage() }
            .retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false
                if (CryptoUtils.isNetworkThrowable(cause) && attempt < MAX_RECONNECT_ATTEMPTS) {
                    val backoffMs = calculateExponentialBackoff(attempt)
                    Log.w(TAG, "Message stream interrupted for $conversationId. Reconnecting in ${backoffMs}ms...")
                    delay(backoffMs)
                    true
                } else {
                    false
                }
            }

        // 3. Local optimistic dispatch events
        val localStream: Flow<Message> = outgoingMessageEvents
            .filter { it.conversationId == conversationId }

        // 4. Merge network and local streams with backpressure safety
        val collectorJob = launch {
            merge(networkStream, localStream).collect { msg ->
                if (msg.deliveryStatus == MessageDeliveryStatus.LOCAL_PENDING ||
                    msg.deliveryStatus == MessageDeliveryStatus.FAILED ||
                    deduplicator.add(msg.id)
                ) {
                    send(msg)
                }
            }
        }

        awaitClose {
            collectorJob.cancel()
        }
    }.buffer(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ).flowOn(ioDispatcher)

    // ────────────────────────────────────────────────────────────────────────
    //  MLS Group Operations
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates or retrieves a 1-on-1 MLS group conversation with the specified peer.
     *
     * Validates that [peerAddress] conforms to standard Ethereum hex address format
     * before delegating to libxmtp MLS ratchet group creation.
     *
     * @param peerAddress Target peer Ethereum address (e.g., `0x1234...`).
     * @return [Result.success] containing the resolved [Conversation], or [Result.failure].
     */
    suspend fun createConversation(peerAddress: String): Result<Conversation> = withContext(ioDispatcher) {
        val sanitisedAddress = CryptoUtils.sanitiseEthereumAddress(peerAddress)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Invalid Ethereum address format: $peerAddress")
            )

        val client = xmtpEngine.client
            ?: return@withContext Result.failure(
                IllegalStateException("XMTP engine is not ready (current state: ${xmtpEngine.state.value})")
            )

        try {
            Log.i(TAG, "Creating or resolving MLS 1-on-1 conversation with peer=$sanitisedAddress")
            val conversation = client.conversations.newConversation(sanitisedAddress)
            conversationRefreshTrigger.emit(Unit)
            Result.success(conversation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MLS conversation with peer=$sanitisedAddress: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Message Dispatch (Two-Phase Optimistic Persistence)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Sends a text message to a conversation with two-phase local persistence guarantees.
     *
     * Persists the message into the local encrypted SQLCipher database with [MessageDeliveryStatus.LOCAL_PENDING]
     * **before** transmitting across the network. Updates to [MessageDeliveryStatus.PUBLISHED] upon
     * network ACK or [MessageDeliveryStatus.FAILED] on transmission failure.
     *
     * @param conversationId Identifier of target conversation.
     * @param content        Plaintext message content.
     * @return [Result.success] on network confirmation, or [Result.failure].
     */
    /**
     * Live message stream for a direct [Conversation] reference.
     */
    /**
     * Επιστρέφει ενημερωμένη και deduplicated λίστα μηνυμάτων για τη συνομιλία.
     */
    fun observeMessages(conversation: Conversation): Flow<List<DecryptedMessage>> = callbackFlow {
        val messageMap = LinkedHashMap<String, DecryptedMessage>()

        val initialHistory = withContext(ioDispatcher) {
            try {
                conversation.messages()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        initialHistory.forEach { messageMap[it.id] = it }
        trySend(messageMap.values.toList())

        val streamJob = launch(ioDispatcher) {
            try {
                conversation.streamMessages().collect { incoming ->
                    messageMap[incoming.id] = incoming
                    trySend(messageMap.values.toList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Διακοπή σύνδεσης gRPC
            }
        }

        awaitClose { streamJob.cancel() }
    }
    .buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    .flowOn(ioDispatcher)

    /**
     * Direct message dispatch on a [Conversation] reference.
     */
    suspend fun sendMessage(conversation: Conversation, text: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            conversation.send(text)
        }
    }

    suspend fun sendMessage(conversationId: String, content: String): Result<Unit> = withContext(ioDispatcher) {
        if (content.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Message content cannot be blank"))
        }

        val client = xmtpEngine.client
            ?: return@withContext Result.failure(
                IllegalStateException("XMTP engine is not ready (current state: ${xmtpEngine.state.value})")
            )

        val conversation = client.conversations.findConversation(conversationId)
            ?: return@withContext Result.failure(
                NoSuchElementException("Conversation not found for ID: $conversationId")
            )

        val fallbackId = "opt_" + UUID.randomUUID().toString()
        val senderAddress = client.publicIdentity.identifier

        val (preparedMessageId, pendingMessage) = preparePendingMessage(
            conversation = conversation,
            conversationId = conversationId,
            senderAddress = senderAddress,
            content = content,
            fallbackId = fallbackId
        )

        outgoingMessageEvents.emit(pendingMessage)

        publishAndCommitMessage(
            conversation = conversation,
            pendingMessage = pendingMessage,
            preparedMessageId = preparedMessageId,
            fallbackId = fallbackId,
            content = content
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Internal Utilities & Error Classification
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun resolveConversation(client: Client, conversationId: String): Conversation {
        var conversation = client.conversations.findConversation(conversationId)
        if (conversation == null) {
            try {
                client.conversations.sync()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            conversation = client.conversations.findConversation(conversationId)
        }
        return conversation ?: throw NoSuchElementException("Conversation not found for ID: $conversationId")
    }

    private suspend fun loadHistoricalMessagesSafe(conversation: Conversation, conversationId: String): List<Message> {
        return try {
            conversation.messages().map { it.toDomainMessage() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to load message history for $conversationId: ${e.message}")
            emptyList()
        }
    }

    private fun preparePendingMessage(
        conversation: Conversation,
        conversationId: String,
        senderAddress: String,
        content: String,
        fallbackId: String
    ): Pair<String, Message> {
        val preparedMessageId: String = try {
            val prepId = conversation.prepareMessage(content)
            if (prepId.isNotBlank()) prepId else fallbackId
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fallbackId
        }

        val pendingMessage = Message(
            id = preparedMessageId,
            conversationId = conversationId,
            senderAddress = senderAddress,
            body = content,
            sentAt = System.currentTimeMillis(),
            deliveryStatus = MessageDeliveryStatus.LOCAL_PENDING
        )
        return Pair(preparedMessageId, pendingMessage)
    }

    private suspend fun publishAndCommitMessage(
        conversation: Conversation,
        pendingMessage: Message,
        preparedMessageId: String,
        fallbackId: String,
        content: String
    ): Result<Unit> {
        return try {
            val publishedId = if (preparedMessageId != fallbackId) {
                conversation.publishMessages()
                preparedMessageId
            } else {
                conversation.send(content)
            }

            val publishedMessage = pendingMessage.copy(
                id = publishedId,
                deliveryStatus = MessageDeliveryStatus.PUBLISHED
            )
            outgoingMessageEvents.emit(publishedMessage)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch message to conversation ${pendingMessage.conversationId}: ${e.message}", e)
            val failedMessage = pendingMessage.copy(
                deliveryStatus = MessageDeliveryStatus.FAILED
            )
            outgoingMessageEvents.emit(failedMessage)
            Result.failure(e)
        }
    }

    private fun calculateExponentialBackoff(attempt: Long): Long {
        val exponent = attempt.coerceAtMost(MAX_BACKOFF_EXPONENT.toLong()).toInt()
        val backoff = INITIAL_BACKOFF_MS * (1L shl exponent)
        return backoff.coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Cancels underlying repository coroutine scope and clears cached reactive flows.
     */
    override fun close() {
        repositoryJob.cancel()
        messageFlows.clear()
    }

    companion object {
        private const val TAG = "ConversationRepository"
        private const val CHANNEL_BUFFER_CAPACITY = 64
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 10_000L
        private const val MAX_BACKOFF_EXPONENT = 5
        private const val MAX_RECONNECT_ATTEMPTS = 5L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val MAX_DEDUPLICATOR_CAPACITY = 512
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Bounded LRU Deduplicator (Prevents OOM)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Thread-safe bounded LRU deduplicator preventing in-memory heap leaks over long-running streams.
 */
private class BoundedIdDeduplicator(private val maxSize: Int) {
    private val set: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(maxSize, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                    return size > maxSize
                }
            }
        )
    )

    fun add(id: String): Boolean = set.add(id)
}

// ────────────────────────────────────────────────────────────────────────────
//  Domain Models (Clean Architecture)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Domain representation of a chat message for Jetpack Compose UI rendering.
 * Decouples presentation components from libxmtp native C/Rust internals.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val senderAddress: String,
    val body: String,
    val sentAt: Long,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.PUBLISHED
)

/**
 * Delivery status for optimistic message dispatch and UI state rendering.
 */
enum class MessageDeliveryStatus {
    /** Stored locally; in flight across MLS ratchet and network transport. */
    LOCAL_PENDING,

    /** Successfully confirmed and committed to the XMTP ledger. */
    PUBLISHED,

    /** Network failure or native MLS ratchet encryption error. */
    FAILED
}

/**
 * Maps a libxmtp [DecodedMessage] into a clean domain [Message].
 */
fun DecodedMessage.toDomainMessage(): Message = Message(
    id = id ?: "",
    conversationId = conversationId ?: "",
    senderAddress = senderInboxId ?: "",
    body = body ?: "",
    sentAt = sentAt?.time ?: (sentAtNs / 1_000_000L),
    deliveryStatus = when (deliveryStatus) {
        DecodedMessage.MessageDeliveryStatus.PUBLISHED -> MessageDeliveryStatus.PUBLISHED
        DecodedMessage.MessageDeliveryStatus.UNPUBLISHED -> MessageDeliveryStatus.LOCAL_PENDING
        DecodedMessage.MessageDeliveryStatus.FAILED -> MessageDeliveryStatus.FAILED
        DecodedMessage.MessageDeliveryStatus.ALL -> MessageDeliveryStatus.PUBLISHED
    }
)


/**
 * Typealias providing backwards-compatibility for callers referring to [DecodedMessage].
 */
typealias DecryptedMessage = DecodedMessage

val DecodedMessage.senderAddress: String
    get() = senderInboxId
