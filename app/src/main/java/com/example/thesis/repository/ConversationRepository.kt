package com.example.thesis.repository

import android.util.Log
import com.example.thesis.identity.InMemoryNicknameStorage
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameStorage
import com.example.thesis.notifications.InMemorySeenMessageStorage
import com.example.thesis.notifications.SeenMessageStorage
import com.example.thesis.util.CryptoUtils
import com.example.thesis.xmtp.CallSignal
import com.example.thesis.xmtp.CallSignalCodec
import com.example.thesis.xmtp.ContentTypeCallSignal
import com.example.thesis.xmtp.ContentTypeProfileNickname
import com.example.thesis.xmtp.ProfileNickname
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
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
import org.xmtp.android.library.ConsentState
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.Dm
import org.xmtp.android.library.SendOptions
import org.xmtp.android.library.codecs.ContentTypeReadReceipt
import org.xmtp.android.library.codecs.ContentTypeText
import org.xmtp.android.library.codecs.ReadReceipt
import org.xmtp.android.library.libxmtp.DecodedMessage
import org.xmtp.proto.mls.message.contents.TranscriptMessages.GroupUpdated
import org.xmtp.android.library.libxmtp.PublicIdentity
import org.xmtp.android.library.libxmtp.IdentityKind
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PolledMessagesBatch(
    val conversations: List<Conversation>,
    val newMessages: List<DecodedMessage>
)

data class IncomingCallSignal(
    val messageId: String,
    val conversationId: String,
    val senderInboxId: String,
    val sentAtMs: Long,
    val signal: CallSignal
)

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val syncIntervalMs: Long = 5_000L,
    private val seenMessageStorage: SeenMessageStorage = InMemorySeenMessageStorage(),
    private val nicknameStorage: NicknameStorage = InMemoryNicknameStorage()
) : AutoCloseable {

    val xmtpEngine: XmtpEngine get() = engine

    constructor(
        xmtpEngine: XmtpEngine,
        ioDispatcher: CoroutineDispatcher
    ) : this(
        engine = xmtpEngine,
        repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher),
        ioDispatcher = ioDispatcher,
        syncIntervalMs = 5_000L
    )

    constructor(
        xmtpEngine: XmtpEngine,
        ioDispatcher: CoroutineDispatcher,
        syncIntervalMs: Long
    ) : this(
        engine = xmtpEngine,
        repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher),
        ioDispatcher = ioDispatcher,
        syncIntervalMs = syncIntervalMs
    )

    private val repositoryJob = SupervisorJob(repositoryScope.coroutineContext[Job])
    private val internalScope = CoroutineScope(repositoryScope.coroutineContext + repositoryJob + ioDispatcher)

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
     * Internal trigger notifying message stream observers when a new message is sent or synced locally.
     */
    private val messageRefreshTrigger = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Bus compartido para despachar notificaciones de prioridad de mensajes nuevos sin acoplar UI a gRPC.
    private val _incomingMessages = MutableSharedFlow<DecodedMessage>(
        extraBufferCapacity = CHANNEL_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: SharedFlow<DecodedMessage> = _incomingMessages.asSharedFlow()

    // Cargamos los identificadores ya notificados para que un despertar en frio detecte deltas reales sin repetir alertas.
    private val seenMessageIds = BoundedIdDeduplicator(maxSize = MAX_DEDUPLICATOR_CAPACITY).apply {
        addAll(seenMessageStorage.loadSeenIds())
    }

    // Bus separado para tramas efimeras de senalizacion WebRTC sin mezclarlas con notificaciones de texto.
    private val _incomingCallSignals = MutableSharedFlow<IncomingCallSignal>(
        extraBufferCapacity = CHANNEL_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingCallSignals: SharedFlow<IncomingCallSignal> = _incomingCallSignals.asSharedFlow()

    private val processedCallSignalIds = BoundedIdDeduplicator(maxSize = MAX_DEDUPLICATOR_CAPACITY)

    private val _peerReadTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val peerReadTimestamps: StateFlow<Map<String, Long>> = _peerReadTimestamps.asStateFlow()

    private val _localReadTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val localReadTimestamps: StateFlow<Map<String, Long>> = _localReadTimestamps.asStateFlow()

    @Volatile
    private var hasSeededMessageBaseline: Boolean = seenMessageStorage.isBaselineEstablished()

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

    init {
        if (syncIntervalMs > 0L) {
            internalScope.launch {
                readyClientFlow.collectLatest { client ->
                    while (isActive) {
                        try {
                            client.conversations.streamAllMessages().collect { msg ->
                                // Extraemos acuses de lectura y apodos antes de descartar tramas no textuales.
                                processControlMessages(msg.conversationId, listOf(msg), emitLiveCallSignals = true)
                                if (!msg.isDisplayableUserMessage()) return@collect
                                if (recordSeenId(msg.id)) {
                                    _incomingMessages.tryEmit(msg)
                                }
                                messageRefreshTrigger.emit(msg.conversationId)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "streamAllMessages disconnected: ${e::class.java.simpleName}. Reconnecting in 2s...")
                            delay(2_000L)
                        }
                    }
                }
            }
        }
    }

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
                scope = internalScope,
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

    private suspend fun fetchConversationsWithConsent(client: Client): List<Conversation> {
        val allowedConsentStates = listOf(ConsentState.ALLOWED, ConsentState.UNKNOWN)
        try {
            client.conversations.sync()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Conversations sync (welcomes) failed: ${e.message}")
        }
        var syncAllSucceeded = true
        try {
            client.conversations.syncAllConversations(consentStates = allowedConsentStates)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            syncAllSucceeded = false
            Log.w(TAG, "Conversations syncAllConversations failed: ${e.message}")
        }
        val list = try {
            client.conversations.list(consentStates = allowedConsentStates)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            client.conversations.list()
        }
        list.forEach { conv ->
            if (!syncAllSucceeded) {
                // Si la sincronizacion global falla, sincronizamos cada conversacion individualmente para no perder mensajes nuevos.
                try {
                    conv.sync()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Fallback per-conversation sync failed: ${e::class.java.simpleName}")
                }
            }
            try {
                if (conv.consentState() == ConsentState.UNKNOWN) {
                    conv.updateConsentState(ConsentState.ALLOWED)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Non-fatal consent update error: ${e::class.java.simpleName}")
            }
        }
        return list
    }

    private fun createConversationsPipeline(client: Client): Flow<List<Conversation>> = channelFlow {
        // 1. Instantly emit local cached conversations from SQLCipher (0ms latency, eliminates cold-start delay)
        val initialCached = try {
            client.conversations.list()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
        val currentList = initialCached.toMutableList()
        if (currentList.isNotEmpty()) {
            send(currentList.toList())
        }

        // 2. Initial sync with network ledger discovering both ALLOWED and UNKNOWN incoming invites
        val syncedList = fetchConversationsWithConsent(client)
        if (currentList.isEmpty() || syncedList != currentList) {
            currentList.clear()
            currentList.addAll(syncedList)
            send(currentList.toList())
        }

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
                    try {
                        if (newConv.consentState() == ConsentState.UNKNOWN) {
                            newConv.updateConsentState(ConsentState.ALLOWED)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Non-fatal streamed consent update error: ${e::class.java.simpleName}")
                    }
                    val existingIndex = currentList.indexOfFirst { it.id == newConv.id }
                    if (existingIndex >= 0) {
                        currentList[existingIndex] = newConv
                    } else {
                        currentList.add(0, newConv)
                        launch { emitLatestDisplayableMessage(newConv) }
                    }
                } else {
                    val refreshed = fetchConversationsWithConsent(client)
                    currentList.clear()
                    currentList.addAll(refreshed)
                }
                send(currentList.toList())
            }
        }

        // Periodic fallback network pull to discover peer invites if gRPC stream misses a frame
        val autoPollJob = if (syncIntervalMs > 0L) {
            launch(ioDispatcher) {
                while (isActive) {
                    delay(maxOf(syncIntervalMs * 2L, 10_000L))
                    try {
                        val refreshed = fetchConversationsWithConsent(client)
                        if (refreshed.size != currentList.size || refreshed.map { it.id } != currentList.map { it.id }) {
                            val existingIds = currentList.map { it.id }.toSet()
                            currentList.clear()
                            currentList.addAll(refreshed)
                            send(currentList.toList())
                            val newlyDiscovered = refreshed.filter { it.id !in existingIds }
                            if (newlyDiscovered.isNotEmpty()) {
                                launch {
                                    newlyDiscovered.forEach { emitLatestDisplayableMessage(it) }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Non-fatal periodic poll error
                    }
                }
            }
        } else null

        awaitClose {
            collectorJob.cancel()
            autoPollJob?.cancel()
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
                    scope = internalScope,
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
            .filter { it.isDisplayableUserMessage() }
            .map { it.toDomainMessage() }
            .retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false
                if (CryptoUtils.isNetworkThrowable(cause) && attempt < MAX_RECONNECT_ATTEMPTS) {
                    val backoffMs = calculateExponentialBackoff(attempt)
                    Log.w(TAG, "Message stream interrupted [conversation=REDACTED]. Reconnecting in ${backoffMs}ms...")
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
    /**
     * Creates or retrieves a local MLS Group conversation for loopback testing and benchmarking.
     *
     * In XMTP v4 MLS, 1-on-1 direct messages cannot be opened with oneself (enforcing
     * "Recipient is sender" safety). Loopback testing is achieved via an MLS Group
     * where the current user is the sole member/admin.
     */
    suspend fun createLoopbackConversation(): Result<Conversation> = withContext(ioDispatcher) {
        val client = xmtpEngine.client
            ?: return@withContext Result.failure(
                IllegalStateException("XMTP engine is not ready (current state: ${xmtpEngine.state.value})")
            )
        try {
            Log.i(TAG, "Creating MLS Loopback Group for self-benchmarking")
            val group = client.conversations.newGroup(
                inboxIds = emptyList<String>(),
                groupName = "MLS Research Loopback"
            )
            val conversation = Conversation.Group(group)
            conversationRefreshTrigger.emit(Unit)
            Result.success(conversation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MLS loopback session: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Creates or retrieves an MLS conversation with the specified peer Ethereum address.
     *
     * Validates that [peerAddress] conforms to standard Ethereum hex address format
     * before delegating to libxmtp PublicIdentity resolution.
     */
    suspend fun createConversation(peerIdentifier: String): Result<Conversation> = withContext(ioDispatcher) {
        val client = xmtpEngine.client
            ?: return@withContext Result.failure(
                IllegalStateException("XMTP engine is not ready (current state: ${xmtpEngine.state.value})")
            )

        val trimmed = peerIdentifier.trim()
        val sanitisedEthAddress = CryptoUtils.sanitiseEthereumAddress(trimmed)
        val sanitisedInboxId = CryptoUtils.sanitiseInboxId(trimmed)

        if (sanitisedEthAddress == null && sanitisedInboxId == null) {
            return@withContext Result.failure(
                IllegalArgumentException("Invalid peer format: Provide a valid Ethereum address (0x...) or XMTP Inbox ID (64-hex)")
            )
        }

        try {
            val dm: Dm = if (sanitisedInboxId != null) {
                if (sanitisedInboxId.equals(client.inboxId, ignoreCase = true)) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Δεν μπορείτε να ανοίξετε 1-on-1 DM με το δικό σας Inbox ID. Χρησιμοποιήστε το Loopback.")
                    )
                }
                Log.i(TAG, "Resolving MLS conversation via findOrCreateDm [peer=REDACTED]")
                client.conversations.findOrCreateDm(sanitisedInboxId)
            } else {
                val ethAddress = sanitisedEthAddress
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Invalid Ethereum address format")
                    )
                val identity = PublicIdentity(IdentityKind.ETHEREUM, ethAddress)

                val canMessageMap = try {
                    client.canMessage(listOf(identity))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "canMessage check error: ${e.message}")
                    emptyMap()
                }

                val isRegistered = canMessageMap[ethAddress]
                    ?: canMessageMap[identity.identifier]
                    ?: canMessageMap[ethAddress.lowercase()]
                    ?: true

                if (!isRegistered) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Ο χρήστης $ethAddress δεν διαθέτει ενεργά κλειδιά MLS στο δίκτυο XMTP (${client.environment.name})."
                        )
                    )
                }

                Log.i(TAG, "Resolving MLS conversation via PublicIdentity(ETHEREUM) [peer=REDACTED]")
                client.conversations.findOrCreateDmWithIdentity(identity)
            }

            try {
                dm.updateConsentState(ConsentState.ALLOWED)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not update DM consent state: ${e.message}")
            }

            try {
                dm.sync()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }

            val conversation = Conversation.Dm(dm)
            conversationRefreshTrigger.emit(Unit)
            Result.success(conversation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MLS conversation [peer=REDACTED]: ${e::class.java.simpleName}")
            Result.failure(e)
        }
    }

    suspend fun pollIncomingMessages(seedOnly: Boolean = false): Result<Int> =
        pollIncomingMessagesAndCollect(seedOnly).map { it.newMessages.size }

    suspend fun pollIncomingMessagesAndCollect(seedOnly: Boolean = false): Result<PolledMessagesBatch> =
        withContext(ioDispatcher) {
            val client = xmtpEngine.client
                ?: return@withContext Result.failure(
                    IllegalStateException("XMTP engine is not ready (current state: ${xmtpEngine.state.value})")
                )
            runCatching {
                val conversations = fetchConversationsWithConsent(client)
                val newMessages = mutableListOf<DecodedMessage>()
                var hasConversationUpdates = false
                var hasSeenIdUpdates = false
                val shouldSeed = seedOnly || !hasSeededMessageBaseline

                for (conv in conversations) {
                    val rawMsgs = fetchMessagesSafely(conv)
                    processControlMessages(conv.id, rawMsgs, emitLiveCallSignals = !shouldSeed)
                    val msgs = rawMsgs
                        .filter { it.isDisplayableUserMessage() }
                        .sortedBy { it.resolvedTimestampMs() }

                    if (shouldSeed) {
                        val seedIds = msgs.mapNotNull { it.id.takeIf(String::isNotBlank) }
                        if (seenMessageIds.addAll(seedIds)) {
                            hasSeenIdUpdates = true
                        }
                    } else {
                        var convHasNew = false
                        msgs.forEach { msg ->
                            if (msg.id.isNotBlank() && seenMessageIds.add(msg.id)) {
                                _incomingMessages.tryEmit(msg)
                                newMessages.add(msg)
                                convHasNew = true
                                hasSeenIdUpdates = true
                            }
                        }
                        if (convHasNew) {
                            messageRefreshTrigger.tryEmit(conv.id)
                            hasConversationUpdates = true
                        }
                    }
                }

                if (shouldSeed) {
                    hasSeededMessageBaseline = true
                    if (hasSeenIdUpdates || !seenMessageStorage.isBaselineEstablished()) {
                        seenMessageStorage.saveSeenIds(seenMessageIds.snapshot())
                        seenMessageStorage.setBaselineEstablished(true)
                    }
                } else {
                    if (hasSeenIdUpdates) {
                        seenMessageStorage.saveSeenIds(seenMessageIds.snapshot())
                    }
                    if (hasConversationUpdates) {
                        conversationRefreshTrigger.tryEmit(Unit)
                    }
                }
                PolledMessagesBatch(conversations = conversations, newMessages = newMessages)
            }
        }

    fun resetNotificationBaseline() {
        hasSeededMessageBaseline = false
        seenMessageIds.clear()
        processedCallSignalIds.clear()
        seenMessageStorage.clear()
        _peerReadTimestamps.value = emptyMap()
        _localReadTimestamps.value = emptyMap()
    }

    // Recargamos la linea base desde almacenamiento tras restaurar mensajes vistos en una migracion.
    fun reloadNotificationBaselineFromStorage() {
        seenMessageIds.clear()
        seenMessageIds.addAll(seenMessageStorage.loadSeenIds())
        hasSeededMessageBaseline = seenMessageStorage.isBaselineEstablished()
    }

    suspend fun syncConversation(conversation: Conversation): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            conversation.sync()
            messageRefreshTrigger.emit(conversation.id)
        }
    }

    /**
     * Triggers an explicit network sync of MLS conversations from the XMTP ledger.
     */
    suspend fun syncConversations(): Result<Unit> = withContext(ioDispatcher) {
        val client = xmtpEngine.client
            ?: return@withContext Result.failure(
                IllegalStateException("XMTP engine is not ready (current state: ${xmtpEngine.state.value})")
            )
        runCatching {
            fetchConversationsWithConsent(client)
            conversationRefreshTrigger.emit(Unit)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Message Dispatch (Two-Phase Optimistic Persistence)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Observes a deduplicated, chronologically sorted live message stream for [conversation].
     */
    fun observeMessages(conversation: Conversation): Flow<List<DecryptedMessage>> = callbackFlow {
        val mapLock = Any()
        val messageMap = LinkedHashMap<String, DecryptedMessage>()
        val syncChannel = Channel<Unit>(Channel.CONFLATED)

        fun getSortedListLocked(): List<DecryptedMessage> {
            return messageMap.values.sortedWith(
                compareBy<DecryptedMessage> { it.sentAt?.time ?: (it.sentAtNs / 1_000_000L) }
                    .thenBy { it.id }
            )
        }

        // 1. Instantly load local cached messages from SQLite to eliminate initial UI latency (0ms wait)
        val initialCached = fetchMessagesSafely(conversation)
        processControlMessages(conversation.id, initialCached)
        val initialCachedIds = mutableListOf<String>()
        val initialSnapshot = synchronized(mapLock) {
            initialCached.filter { it.isDisplayableUserMessage() }.forEach { msg ->
                if (msg.id.isNotBlank()) {
                    initialCachedIds.add(msg.id)
                }
                messageMap[msg.id] = msg
            }
            getSortedListLocked()
        }
        recordSeenIdsBatch(initialCachedIds)
        trySend(initialSnapshot)

        // 2. Dedicated single-flight sync processor consuming from CONFLATED channel
        val syncProcessorJob = launch(ioDispatcher) {
            for (signal in syncChannel) {
                try {
                    conversation.sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "observeMessages: conversation.sync failed: ${e::class.java.simpleName}")
                }

                val latest = fetchMessagesSafely(conversation)
                processControlMessages(conversation.id, latest, emitLiveCallSignals = true)

                val filteredLatest = latest.filter { it.isDisplayableUserMessage() }
                val newlySyncedMessages = mutableListOf<DecodedMessage>()
                val updatedSnapshot = synchronized(mapLock) {
                    var hasChanges = filteredLatest.size != messageMap.size
                    filteredLatest.forEach { msg ->
                        val prev = messageMap[msg.id]
                        if (prev == null) {
                            newlySyncedMessages.add(msg)
                        }
                        if (prev != msg) {
                            messageMap[msg.id] = msg
                            hasChanges = true
                        }
                    }
                    if (hasChanges) getSortedListLocked() else null
                }
                newlySyncedMessages.forEach { msg ->
                    if (recordSeenId(msg.id)) {
                        _incomingMessages.tryEmit(msg)
                    }
                }
                if (updatedSnapshot != null) {
                    trySend(updatedSnapshot)
                }
            }
        }

        // Trigger immediate background network sync upon entering
        syncChannel.trySend(Unit)

        // 3. Real-time gRPC stream for incoming push messages
        val streamJob = launch(ioDispatcher) {
            while (isActive) {
                try {
                    conversation.streamMessages().collect { incoming ->
                        processControlMessages(conversation.id, listOf(incoming), emitLiveCallSignals = true)
                        if (!incoming.isDisplayableUserMessage()) return@collect
                        if (recordSeenId(incoming.id)) {
                            _incomingMessages.tryEmit(incoming)
                        }
                        Log.i(TAG, "RX COMMITTED [conversation=REDACTED]")
                        val streamSnapshot = synchronized(mapLock) {
                            messageMap[incoming.id] = incoming
                            getSortedListLocked()
                        }
                        trySend(streamSnapshot)
                        syncChannel.trySend(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "streamMessages disconnected [conversation=REDACTED]: ${e::class.java.simpleName}. Reconnecting in 1s...")
                    delay(1000L)
                }
            }
        }

        // 4. Periodic fallback sync ticker when live gRPC stream is idle
        val pollJob = if (syncIntervalMs > 0L) {
            launch(ioDispatcher) {
                while (isActive) {
                    delay(maxOf(syncIntervalMs, 3_000L))
                    syncChannel.trySend(Unit)
                }
            }
        } else null

        // 5. Instant reload upon local message dispatch, network push, or explicit sync
        val refreshJob = launch(ioDispatcher) {
            messageRefreshTrigger.collect { triggerId ->
                if (triggerId.isBlank() ||
                    triggerId.equals(conversation.id, ignoreCase = true) ||
                    triggerId.equals(conversation.topic, ignoreCase = true)
                ) {
                    syncChannel.trySend(Unit)
                }
            }
        }

        awaitClose {
            streamJob.cancel()
            pollJob?.cancel()
            refreshJob.cancel()
            syncProcessorJob.cancel()
            syncChannel.close()
        }
    }
    .buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    .flowOn(ioDispatcher)

    /**
     * Direct message dispatch on a [Conversation] reference with instant local preparation (optimistic UI).
     */
    suspend fun sendMessage(conversation: Conversation, text: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val prepId = try {
                conversation.prepareMessage(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "prepareMessage fallback: ${e.message}")
                null
            }

            // Instantly emit to local flow so user sees the message in 0ms (UNPUBLISHED)
            messageRefreshTrigger.emit(conversation.id)

            val msgId = if (prepId != null && prepId.isNotBlank()) {
                try {
                    conversation.publishMessages()
                    prepId
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "publishMessages failed, falling back to send: ${e.message}")
                    conversation.send(text)
                }
            } else {
                conversation.send(text)
            }

            try {
                conversation.sync()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Post-send sync failed: ${e::class.java.simpleName}")
            }

            // Trigger flow update to transition message to PUBLISHED state with server timestamp
            messageRefreshTrigger.emit(conversation.id)
            Log.i(TAG, "TX COMMITTED [conversation=REDACTED]")
            msgId
        }
    }

    suspend fun sendReadReceipt(conversation: Conversation): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val msgId = conversation.send(
                ReadReceipt,
                SendOptions(contentType = ContentTypeReadReceipt)
            )
            val nowMs = System.currentTimeMillis()
            _localReadTimestamps.update { current ->
                val prev = current[conversation.id] ?: 0L
                if (nowMs > prev) current + (conversation.id to nowMs) else current
            }
            try {
                conversation.sync()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            msgId
        }
    }

    suspend fun sendProfileNickname(conversation: Conversation, nickname: String): Result<String> =
        withContext(ioDispatcher) {
            val clean = NicknamePolicy.sanitize(nickname)
                ?: return@withContext Result.failure(IllegalArgumentException("Nickname cannot be blank"))
            runCatching {
                val msgId = conversation.send(
                    ProfileNickname(clean),
                    SendOptions(contentType = ContentTypeProfileNickname)
                )
                try {
                    conversation.sync()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                msgId
            }
        }

    suspend fun sendCallSignal(conversationId: String, signal: CallSignal): Result<String> =
        withContext(ioDispatcher) {
            val client = xmtpEngine.client
                ?: return@withContext Result.failure(
                    IllegalStateException("XMTP engine is not ready (current state: ${xmtpEngine.state.value})")
                )
            // Buscamos la conversacion dentro de runCatching para que un fallo de red no escape a la corrutina de llamada.
            runCatching {
                val conversation = client.conversations.findConversation(conversationId)
                    ?: throw NoSuchElementException("Conversation not found for ID: $conversationId")
                val msgId = conversation.send(
                    signal,
                    SendOptions(contentType = ContentTypeCallSignal)
                )
                try {
                    conversation.sync()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                msgId
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

    private suspend fun emitLatestDisplayableMessage(conversation: Conversation) {
        val latest = fetchMessagesSafely(conversation)
            .filter { it.isDisplayableUserMessage() }
            .maxByOrNull { it.sentAt?.time ?: (it.sentAtNs / 1_000_000L) }
        if (latest != null && recordSeenId(latest.id)) {
            _incomingMessages.tryEmit(latest)
        }
    }

    private suspend fun fetchMessagesSafely(conversation: Conversation): List<DecodedMessage> {
        return try {
            conversation.messages()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            try {
                conversation.messages()
            } catch (retryEx: CancellationException) {
                throw retryEx
            } catch (retryEx: Exception) {
                Log.w(TAG, "Failed to query cached messages: ${retryEx::class.java.simpleName}")
                emptyList()
            }
        }
    }

    private suspend fun loadHistoricalMessagesSafe(conversation: Conversation, conversationId: String): List<Message> {
        return try {
            conversation.messages()
                .filter { it.isDisplayableUserMessage() }
                .map { it.toDomainMessage() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to load message history [conversation=REDACTED]: ${e::class.java.simpleName}")
            emptyList()
        }
    }

    private suspend fun preparePendingMessage(
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
            Log.i(TAG, "TX COMMITTED [conversation=REDACTED]")
            outgoingMessageEvents.emit(publishedMessage)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch message [conversation=REDACTED]: ${e::class.java.simpleName}")
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
    private fun recordSeenId(id: String): Boolean {
        if (id.isBlank()) return false
        val added = seenMessageIds.add(id)
        if (added) {
            seenMessageStorage.saveSeenIds(seenMessageIds.snapshot())
        }
        return added
    }

    private fun processControlMessages(
        conversationId: String,
        rawMessages: List<DecodedMessage>,
        emitLiveCallSignals: Boolean = false
    ) {
        if (conversationId.isBlank() || rawMessages.isEmpty()) return
        val localInboxId = xmtpEngine.client?.inboxId.orEmpty()

        val peerReadTs = ReadReceiptPolicy.computePeerLastReadTimestampMs(rawMessages, localInboxId)
        if (peerReadTs != null) {
            _peerReadTimestamps.update { current ->
                val prev = current[conversationId] ?: 0L
                if (peerReadTs > prev) current + (conversationId to peerReadTs) else current
            }
        }

        val localReadTs = ReadReceiptPolicy.computeLocalLastReadTimestampMs(rawMessages, localInboxId)
        if (localReadTs != null) {
            _localReadTimestamps.update { current ->
                val prev = current[conversationId] ?: 0L
                if (localReadTs > prev) current + (conversationId to localReadTs) else current
            }
        }

        val peerNicknameUpdates = rawMessages
            .asSequence()
            .filter { msg ->
                val sender = msg.senderInboxId
                sender.isNotBlank() &&
                    (localInboxId.isBlank() || !sender.equals(localInboxId, ignoreCase = true)) &&
                    msg.isProfileNicknameMessage()
            }
            .sortedBy { it.resolvedTimestampMs() }

        for (msg in peerNicknameUpdates) {
            val nickname = msg.extractProfileNickname()
            if (nickname != null) {
                nicknameStorage.setSharedPeerNickname(msg.senderInboxId, nickname)
            }
        }

        val allCallSignalMessages = rawMessages
            .asSequence()
            .filter { it.isCallSignalMessage() }
            .sortedBy { it.resolvedTimestampMs() }
            .toList()

        if (!emitLiveCallSignals) {
            // Marcamos como ya vistas las senales historicas cargadas de cache para no reemitirlas como llamadas nuevas.
            for (msg in allCallSignalMessages) {
                if (msg.id.isNotBlank()) {
                    processedCallSignalIds.add(msg.id)
                }
            }
            return
        }

        // Descartamos ofertas que ya tienen respuesta o fin en el historial (incluyendo REJECT/HANGUP propios).
        val resolvedCallIds = HashSet<String>()
        var latestPeerOfferCallId: String? = null
        for (msg in allCallSignalMessages) {
            val parsed = msg.extractCallSignal() ?: continue
            if (parsed.type != com.example.thesis.xmtp.CallSignalType.OFFER) {
                if (parsed.type != com.example.thesis.xmtp.CallSignalType.ICE_BATCH) {
                    resolvedCallIds.add(parsed.callId)
                }
            } else {
                val sender = msg.senderInboxId
                val isFromPeer = sender.isNotBlank() &&
                    (localInboxId.isBlank() || !sender.equals(localInboxId, ignoreCase = true))
                if (isFromPeer) {
                    latestPeerOfferCallId = parsed.callId
                } else {
                    resolvedCallIds.add(parsed.callId)
                }
            }
        }

        for (msg in allCallSignalMessages) {
            val msgId = msg.id
            val sender = msg.senderInboxId
            val isFromPeer = sender.isNotBlank() &&
                (localInboxId.isBlank() || !sender.equals(localInboxId, ignoreCase = true))
            // Evitamos reemitir la misma senal entre el stream global, el stream del chat y el worker.
            val shouldEmit = if (msgId.isNotBlank()) processedCallSignalIds.add(msgId) else true
            if (!isFromPeer || !shouldEmit) continue

            val parsed = msg.extractCallSignal() ?: continue
            if (parsed.type == com.example.thesis.xmtp.CallSignalType.OFFER) {
                if (parsed.callId in resolvedCallIds) continue
                if (latestPeerOfferCallId != null && parsed.callId != latestPeerOfferCallId) continue
            }
            _incomingCallSignals.tryEmit(
                IncomingCallSignal(
                    messageId = msgId,
                    conversationId = conversationId,
                    senderInboxId = sender,
                    sentAtMs = msg.resolvedTimestampMs().takeIf { it > 0L } ?: parsed.timestampMs,
                    signal = parsed
                )
            )
        }
    }

    private fun recordSeenIdsBatch(ids: Collection<String>) {
        if (seenMessageIds.addAll(ids)) {
            seenMessageStorage.saveSeenIds(seenMessageIds.snapshot())
        }
    }

    override fun close() {
        // Conservamos el estado persistido en disco entre cierres de ciclo; solo resetNotificationBaseline() lo borra en GDPR Shred.
        hasSeededMessageBaseline = false
        seenMessageIds.clear()
        processedCallSignalIds.clear()
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

    fun addAll(ids: Collection<String>): Boolean {
        if (ids.isEmpty()) return false
        var addedAny = false
        synchronized(set) {
            ids.forEach { id ->
                if (id.isNotBlank() && set.add(id)) {
                    addedAny = true
                }
            }
        }
        return addedAny
    }

    fun snapshot(): List<String> = synchronized(set) { set.toList() }

    fun clear() = set.clear()
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

/**
 * Returns true only for user-authored displayable text messages, excluding XMTP v4
 * MLS protocol/transcript control messages such as `xmtp.org/group_updated:1.0`.
 */
internal fun DecodedMessage.isDisplayableUserMessage(): Boolean {
    val contentType = runCatching { encodedContent.type }.getOrNull()
    if (contentType != null) {
        val typeId = runCatching { contentType.typeId }.getOrNull().orEmpty()
        val authorityId = runCatching { contentType.authorityId }.getOrNull().orEmpty()
        if (typeId.isNotEmpty() &&
            !(authorityId == ContentTypeText.authorityId && typeId == ContentTypeText.typeId)
        ) {
            return false
        }
    }
    val decoded = runCatching { content<Any>() }.getOrNull()
    if (decoded is GroupUpdated || decoded is ReadReceipt || decoded is ProfileNickname || decoded is CallSignal) {
        return false
    }
    val text = runCatching { body }.getOrNull()
    return !text.isNullOrBlank()
}

internal fun DecodedMessage.resolvedTimestampMs(): Long {
    val dateMs = runCatching { sentAt?.time }.getOrNull()
    if (dateMs != null && dateMs > 0L) return dateMs
    val ns = runCatching { sentAtNs }.getOrDefault(0L)
    return if (ns > 0L) ns / 1_000_000L else 0L
}

internal fun DecodedMessage.isReadReceiptMessage(): Boolean {
    val ct = runCatching { encodedContent.type }.getOrNull()
    val authority = ct?.let { runCatching { it.authorityId }.getOrNull() }.orEmpty()
    val typeId = ct?.let { runCatching { it.typeId }.getOrNull() }.orEmpty()
    if (authority == ContentTypeReadReceipt.authorityId && typeId == ContentTypeReadReceipt.typeId) {
        return true
    }
    return runCatching { content<Any>() }.getOrNull() is ReadReceipt
}

internal fun DecodedMessage.isProfileNicknameMessage(): Boolean {
    val ct = runCatching { encodedContent.type }.getOrNull()
    val authority = ct?.let { runCatching { it.authorityId }.getOrNull() }.orEmpty()
    val typeId = ct?.let { runCatching { it.typeId }.getOrNull() }.orEmpty()
    if (authority == ContentTypeProfileNickname.authorityId && typeId == ContentTypeProfileNickname.typeId) {
        return true
    }
    return runCatching { content<Any>() }.getOrNull() is ProfileNickname
}

internal fun DecodedMessage.extractProfileNickname(): String? {
    if (!isProfileNicknameMessage()) return null
    val decoded = runCatching { content<Any>() }.getOrNull() as? ProfileNickname
    if (decoded != null) {
        return NicknamePolicy.sanitize(decoded.nickname)
    }
    val rawUtf8 = runCatching { encodedContent.content.toStringUtf8() }.getOrNull()
    return NicknamePolicy.sanitize(rawUtf8)
}

internal fun DecodedMessage.isCallSignalMessage(): Boolean {
    val ct = runCatching { encodedContent.type }.getOrNull()
    val authority = ct?.let { runCatching { it.authorityId }.getOrNull() }.orEmpty()
    val typeId = ct?.let { runCatching { it.typeId }.getOrNull() }.orEmpty()
    if (authority == ContentTypeCallSignal.authorityId && typeId == ContentTypeCallSignal.typeId) {
        return true
    }
    return runCatching { content<Any>() }.getOrNull() is CallSignal
}

internal fun DecodedMessage.extractCallSignal(): CallSignal? {
    if (!isCallSignalMessage()) return null
    val decoded = runCatching { content<Any>() }.getOrNull() as? CallSignal
    if (decoded != null) return decoded
    val rawBytes = runCatching { encodedContent.content.toByteArray() }.getOrNull() ?: return null
    return runCatching { CallSignalCodec.decodeBytes(rawBytes) }.getOrNull()
}

val DecodedMessage.senderAddress: String
    get() = senderInboxId
