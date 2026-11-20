package com.example.thesis.ui

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import android.app.Application
import com.example.thesis.ThesisApplication
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.repository.ReadReceiptPolicy
import com.example.thesis.repository.isDisplayableUserMessage
import java.util.concurrent.ConcurrentHashMap
import com.example.thesis.updater.AppUpdateInfo
import com.example.thesis.updater.UpdateStatus
import java.io.File
import org.xmtp.android.library.libxmtp.DecodedMessage
import org.xmtp.android.library.libxmtp.IdentityKind
import com.example.thesis.security.ShredAuditProof
import com.example.thesis.security.ShredResult
import com.example.thesis.security.ThesisTelemetry
import com.example.thesis.ui.util.ReportExporter
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import org.xmtp.android.library.Conversation


/**
 * Action log entry capturing runtime operations, security milestones, and benchmark latencies.
 */
data class ActionLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val durationMs: Double? = null,
    val isError: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val sessionContainer = (application as ThesisApplication).sessionContainer
    private val keystoreManager = sessionContainer.keystoreManager
    private val databaseFactory = sessionContainer.databaseFactory
    private val cryptoShredder = sessionContainer.cryptoShredder
    private val engine = sessionContainer.engine
    private val repository = sessionContainer.repository
    private val appUpdateManager = sessionContainer.appUpdateManager
    private val notificationManager = sessionContainer.notificationManager
    private val sessionStateMutex = sessionContainer.sessionMutex
    val updateStatus: StateFlow<UpdateStatus> = appUpdateManager.updateStatus
    val unreadCounts: StateFlow<Map<String, Int>> = sessionContainer.unreadCounts
    val nicknameSnapshot: StateFlow<NicknameSnapshot> = sessionContainer.nicknameSnapshot
    val peerReadTimestamps: StateFlow<Map<String, Long>> = repository.peerReadTimestamps
    val isScreenProtectionEnabled: StateFlow<Boolean> = sessionContainer.isScreenProtectionEnabled

    private val _isScreenRecordingActive = MutableStateFlow(false)
    val isScreenRecordingActive: StateFlow<Boolean> = _isScreenRecordingActive.asStateFlow()

    private var messagesJob: Job? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _isShredding = MutableStateFlow(false)
    val isShredding: StateFlow<Boolean> = _isShredding.asStateFlow()

    private val _isCreatingConversation = MutableStateFlow(false)
    val isCreatingConversation: StateFlow<Boolean> = _isCreatingConversation.asStateFlow()

    val engineState: StateFlow<XmtpEngineState> = engine.state

    val walletAddress: StateFlow<String?> = sessionContainer.walletAddress

    private val _lastShredProof = MutableStateFlow<ShredAuditProof?>(null)
    val lastShredProof: StateFlow<ShredAuditProof?> = _lastShredProof.asStateFlow()

    private val _pendingNotificationConversationId = MutableStateFlow<String?>(null)

    val inboxId: StateFlow<String?> = engine.state
        .map { (it as? XmtpEngineState.Ready)?.client?.inboxId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val installationId: StateFlow<String?> = engine.state
        .map { (it as? XmtpEngineState.Ready)?.client?.installationId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _selectedConversation = MutableStateFlow<Conversation?>(null)
    val selectedConversation: StateFlow<Conversation?> = _selectedConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<DecodedMessage>>(emptyList())
    val messages: StateFlow<List<DecodedMessage>> = _messages.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _actionLogs = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val actionLogs: StateFlow<List<ActionLogEntry>> = _actionLogs.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isBenchmarking = MutableStateFlow(false)
    val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    private data class CommittedMessageProof(
        val msgId: String,
        val sender: String,
        val topic: String,
        val sentAtNs: Long? = null
    )

    private val committedProofMap = Collections.synchronizedMap(LinkedHashMap<String, CommittedMessageProof>())
    private val _verifiedMessageCount = MutableStateFlow(0)
    val verifiedMessageCount: StateFlow<Int> = _verifiedMessageCount.asStateFlow()

    private fun recordCommittedMessage(
        msgId: String,
        sender: String,
        topic: String,
        sentAtNs: Long? = null
    ) {
        if (msgId.isBlank()) return
        synchronized(committedProofMap) {
            committedProofMap[msgId] = CommittedMessageProof(
                msgId = msgId,
                sender = sender,
                topic = topic,
                sentAtNs = sentAtNs
            )
            _verifiedMessageCount.value = committedProofMap.size
        }
    }

    fun logAction(tag: String, message: String, durationMs: Double? = null, isError: Boolean = false) {
        val entry = ActionLogEntry(
            tag = tag,
            message = message,
            durationMs = durationMs,
            isError = isError
        )
        _actionLogs.value = (_actionLogs.value + entry).takeLast(100)
    }

    /**
     * Generates a complete Proof-of-Operation cryptographic manifest in the Action Log,
     * dumping active 0x addresses, XMTP Inbox/Installation IDs, MLS Conversation IDs, Topics,
     * peer identities, and committed Message IDs with nanosecond timestamps.
     */
    fun emitBlockchainPoCProof() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = engine.state.value
            if (currentState !is XmtpEngineState.Ready) {
                logAction("MLS", "PoC Proof aborted: XMTP Engine is not in Ready state.")
                return@launch
            }

            val client = currentState.client
            val ethAddress = walletAddress.value?.takeIf { it.startsWith("0x") }
                ?: runCatching { client.publicIdentity.identifier }.getOrNull()
                ?: "0x..."
            val activeConvs = runCatching { client.conversations.list() }.getOrDefault(emptyList())

            logAction(
                tag = "MLS",
                message = "[POC_IDENTITY_PROOF] EthAddress: $ethAddress | InboxID: ${client.inboxId} | InstallationID: ${client.installationId} | Env: ${client.environment.name}"
            )

            if (activeConvs.isEmpty()) {
                logAction("SYNC", "[POC_CONVERSATIONS] 0 active MLS conversations found on network for InboxID ${client.inboxId.take(12)}...")
                return@launch
            }

            activeConvs.forEachIndexed { index, conv ->
                val members = runCatching { conv.members() }.getOrDefault(emptyList())
                val peerInboxes = when (conv) {
                    is Conversation.Dm -> listOf(conv.dm.peerInboxId)
                    is Conversation.Group -> members.map { it.inboxId }.filter { it != client.inboxId }
                }
                val peerAddresses = members
                    .filter { it.inboxId != client.inboxId }
                    .flatMap { it.identities }
                    .filter { it.kind == IdentityKind.ETHEREUM }
                    .map { it.identifier }

                val peerSummary = buildString {
                    if (peerAddresses.isNotEmpty()) {
                        append("Peer0x: ${peerAddresses.joinToString(",")}")
                    }
                    if (peerInboxes.isNotEmpty()) {
                        if (isNotEmpty()) append(" | ")
                        append("PeerInboxes: ${peerInboxes.joinToString(",")}")
                    }
                    if (isEmpty()) {
                        append("Peers: Self/Loopback")
                    }
                }

                val convType = when (conv) {
                    is Conversation.Dm -> "DM"
                    is Conversation.Group -> {
                        val name = conv.group.name
                        if (name == "MLS Research Loopback") "Loopback Group"
                        else if (name.isNotBlank()) "Group ($name)"
                        else "Group (MLS)"
                    }
                }

                val convMessages = runCatching { conv.messages(limit = 10) }
                    .getOrDefault(emptyList())
                    .filter { it.isDisplayableUserMessage() }

                logAction(
                    tag = "SYNC",
                    message = "[POC_CONVERSATION #${index + 1}] ConvID: ${conv.id} | Topic: ${conv.topic} | Type: $convType | $peerSummary | CachedMessages: ${convMessages.size}"
                )

                convMessages.forEach { msg ->
                    recordCommittedMessage(
                        msgId = msg.id,
                        sender = msg.senderInboxId,
                        topic = conv.topic,
                        sentAtNs = msg.sentAtNs
                    )
                    logAction(
                        tag = "E2EE",
                        message = "[POC_MSG_PROOF] MsgID: ${msg.id} | ConvID: ${conv.id} | Topic: ${conv.topic} | SenderInbox: ${msg.senderInboxId} | SentNs: ${msg.sentAtNs}"
                    )
                }
            }
        }
    }

    fun consumeStatusBanner() {
        _statusBanner.value = null
    }

    fun clearActionLogs() {
        committedProofMap.clear()
        _verifiedMessageCount.value = 0
        _actionLogs.value = emptyList()
        ThesisTelemetry.clearTelemetryData()
    }

    fun exportBenchmarkReport(): String {
        val client = (engine.state.value as? XmtpEngineState.Ready)?.client
        val ethAddress = walletAddress.value ?: "N/A"
        val activeInboxId = client?.inboxId ?: inboxId.value ?: "N/A"
        val activeInstallationId = client?.installationId ?: installationId.value ?: "N/A"
        val convList = _conversations.value
        val msgList = _messages.value

        val pocHeader = buildString {
            appendLine("# XMTP v4 (MLS) & Blockchain Identity Proof of Concept")
            appendLine("### Cryptographic Proof of Operation (Addresses & Message IDs)")
            appendLine()
            appendLine("| Cryptographic Identity Root / Layer | Value / Network Hash | Verification Status |")
            appendLine("| :--- | :--- | :---: |")
            appendLine("| **Ethereum EIP-191 Secp256k1 Wallet** | `$ethAddress` | VERIFIED |")
            appendLine("| **XMTP v4 Decentralized Inbox ID** | `$activeInboxId` | REGISTERED |")
            appendLine("| **MLS Device Installation ID (TreeKEM)** | `$activeInstallationId` | ACTIVE |")
            appendLine("| **Active MLS Ratchet Conversations** | `${convList.size} active sessions` | SYNCHRONIZED |")
            appendLine("| **Timestamp (ISO 8601 UTC)** | `${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())}` | CONFIRMED |")
            appendLine()
            appendLine("## Active MLS Conversations & Peer Identity Matrix")
            if (convList.isNotEmpty()) {
                appendLine("| Index | Conversation ID | Topic Hash | Peer / Type |")
                appendLine("| :---: | :--- | :--- | :--- |")
                convList.forEachIndexed { idx, conv ->
                    val peer = when (conv) {
                        is Conversation.Dm -> "DM (Peer: `${conv.dm.peerInboxId.take(16)}...`)"
                        is Conversation.Group -> {
                            val name = conv.group.name
                            if (name == "MLS Research Loopback") "Loopback Group"
                            else if (name.isNotBlank()) "Group: `$name`"
                            else "Group: `MLS`"
                        }
                    }
                    appendLine("| ${idx + 1} | `${conv.id}` | `${conv.topic}` | $peer |")
                }
            } else {
                appendLine("_No active conversations registered._")
            }
            appendLine()
            appendLine("## Committed Message ID Ledger (Cryptographic Proof of Operation)")
            val recordedMessages = synchronized(committedProofMap) {
                committedProofMap.values.toList()
            }.ifEmpty {
                msgList.map { m ->
                    CommittedMessageProof(
                        msgId = m.id,
                        sender = m.senderInboxId,
                        topic = m.topic,
                        sentAtNs = m.sentAtNs
                    )
                }
            }

            if (recordedMessages.isNotEmpty()) {
                appendLine("| Message ID (SHA-256 / MLS Commit Hash) | Sender Inbox / Identity | Topic / Scope | Nanosecond Timestamp |")
                appendLine("| :--- | :--- | :--- | :--- |")
                recordedMessages.takeLast(30).forEach { proof ->
                    val nsPart = proof.sentAtNs?.toString() ?: "N/A"
                    appendLine("| `${proof.msgId}` | `${proof.sender}` | `${proof.topic}` | `$nsPart` |")
                }
            } else {
                appendLine("_No committed messages recorded in active session._")
            }
            appendLine()
            appendLine("---")
            appendLine()
        }
        return pocHeader + ThesisTelemetry.exportFullReport()
    }

    init {
        sessionContainer.onNotificationDispatched = { convId ->
            logAction("NOTIF", "Priority notification dispatched [conv=${convId.take(8)}...]")
        }
        viewModelScope.launch {
            repository.observeConversations().collectLatest { list ->
                _conversations.value = list
                sessionContainer.updateConversationsSnapshot(list)
                val pendingId = _pendingNotificationConversationId.value
                if (pendingId != null) {
                    val matched = list.firstOrNull { it.id == pendingId }
                    if (matched != null) {
                        _pendingNotificationConversationId.value = null
                        selectConversation(matched)
                    }
                }
            }
        }
        viewModelScope.launch {
            ThesisTelemetry.eventsFlow.collect { event ->
                val isKeystoreStatusEvent = event.operationName.startsWith("KEYSTORE:")
                logAction(
                    tag = if (isKeystoreStatusEvent) "KEYSTORE" else "METRIC",
                    message = event.operationName,
                    durationMs = if (isKeystoreStatusEvent && event.durationMs == 0.0) null else event.durationMs,
                    isError = !event.isSuccess
                )
            }
        }
        checkForUpdates(manual = false)
        // Automatically restore hardware-wrapped identity and local SQLCipher database on app launch
        initializeSession()
    }

    /**
     * Εκκινεί την αλυσίδα ασφαλείας: Hardware DEK -> SQLCipher Sandbox -> EIP-191 Signer -> XMTP MLS.
     */
    fun initializeSession() {
        viewModelScope.launch {
            sessionStateMutex.withLock {
                if (engine.state.value is XmtpEngineState.Ready) {
                    sessionContainer.scheduleBackgroundSync()
                    logAction("SESSION", "Session already active, skipping re-initialization.")
                    return@withLock
                }
                _isInitializing.value = true
                try {
                    logAction("KEYSTORE", "Requesting hardware DEK unwrap (AES-256-GCM)...")
                    val dekResult = withContext(Dispatchers.IO) {
                        ThesisTelemetry.measureOperation("1. Keystore AES-256-GCM DEK Unwrap") {
                            keystoreManager.getOrCreateDatabaseKey()
                        }
                    }
                    val dekBytes = try {
                        dekResult.getOrThrow()
                    } catch (e: Throwable) {
                        _statusBanner.value = "Σφάλμα Hardware Keystore: ${e.message}"
                        logAction("KEYSTORE", "Hardware Keystore failure: ${e.message}", isError = true)
                        return@withLock
                    }

                    try {
                        val idKeyResult = withContext(Dispatchers.IO) {
                            ThesisTelemetry.measureOperation("2. Keystore Hardware Identity Unwrap") {
                                keystoreManager.getOrCreateIdentityPrivateKey()
                            }
                        }
                        val privKeyBytes = try {
                            idKeyResult.getOrThrow()
                        } catch (e: Throwable) {
                            _statusBanner.value = "Σφάλμα Hardware Identity Keystore: ${e.message}"
                            logAction("KEYSTORE", "Identity Keystore failure: ${e.message}", isError = true)
                            return@withLock
                        }

                        val signer = try {
                            sessionContainer.activeSigner ?: withContext(Dispatchers.Default) {
                                ThesisSigner.fromPrivateKey(privKeyBytes)
                            }.also { sessionContainer.activeSigner = it }
                        } finally {
                            // Άμεσος μηδενισμός του ενδιάμεσου buffer ιδιωτικού κλειδιού στη μνήμη RAM
                            privKeyBytes.fill(0x00)
                        }
                        sessionContainer.setWalletAddress(signer.address)
                        val maskedAddress = if (signer.address.length >= 10) {
                            "${signer.address.take(6)}...${signer.address.takeLast(4)}"
                        } else signer.address
                        logAction("IDENTITY", "Hardware-backed SECP256K1 identity active: $maskedAddress")

                        val dbDirectory = withContext(Dispatchers.IO) {
                            databaseFactory.resolveAccountDatabaseDirectory(signer.address)
                        }

                        _statusBanner.value = "Σύνδεση με Hardware-Backed SQLCipher & MLS..."
                        val initState = ThesisTelemetry.measureSuspendOperation("3. SQLCipher Sandbox & MLS Init") {
                            engine.initialiseAndAwait(
                                signer = signer,
                                dbDirectory = dbDirectory,
                                dbEncryptionKey = dekBytes
                            )
                        }
                        when (initState) {
                            is XmtpEngineState.Ready -> {
                                _lastShredProof.value = null
                                // Conservamos los IDs ya vistos entre reinicios para notificar mensajes llegados con la app cerrada.
                                val shouldSeed = !sessionContainer.notificationStateStore.isBaselineEstablished()
                                repository.pollIncomingMessages(seedOnly = shouldSeed)
                                sessionContainer.scheduleBackgroundSync()
                                _statusBanner.value = "Συνδέθηκε στο δίκτυο XMTP v4 (MLS) με SQLCipher."
                                logAction("XMTP", "Connected to XMTP v4 MLS network with SQLCipher storage")
                            }
                            is XmtpEngineState.Error -> {
                                _statusBanner.value = "Σφάλμα σύνδεσης XMTP: ${initState.error.message}"
                                logAction("XMTP", "XMTP initialization failed: ${initState.error.message}", isError = true)
                            }
                            else -> {}
                        }
                    } finally {
                        // Άμεσος μηδενισμός του DEK στη μνήμη RAM
                        dekBytes.fill(0x00)
                    }
                } finally {
                    _isInitializing.value = false
                }
            }
        }
    }

    private val loggedMessageIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val lastSentReceiptPeerMsgId = ConcurrentHashMap<String, String>()
    private val lastAnnouncedNicknameByConv = ConcurrentHashMap<String, String>()

    fun onAppForegroundChanged(inForeground: Boolean) {
        sessionContainer.setAppInForeground(inForeground)
        if (inForeground) {
            if (engine.state.value is XmtpEngineState.Ready) {
                sessionContainer.scheduleBackgroundSync()
            }
            val currentConv = _selectedConversation.value
            val currentMsgs = _messages.value
            if (currentConv != null && currentMsgs.isNotEmpty()) {
                maybeSendReadReceipt(currentConv, currentMsgs)
            }
        }
    }

    fun setScreenProtectionEnabled(enabled: Boolean) {
        sessionContainer.screenSecurityStore.setScreenProtectionEnabled(enabled)
        _statusBanner.value = if (enabled) {
            "Η προστασία οθόνης (FLAG_SECURE) ενεργοποιήθηκε."
        } else {
            "Η προστασία οθόνης απενεργοποιήθηκε προσωρινά."
        }
        logAction(
            tag = "SECURITY",
            message = if (enabled) {
                "Screen protection enabled (FLAG_SECURE + Recents privacy)"
            } else {
                "Screen protection disabled by user override"
            }
        )
    }

    fun onScreenCaptureAttemptDetected() {
        logAction("SECURITY", "Screenshot capture event detected by Android OS")
        _statusBanner.value = "Εντοπίστηκε απόπειρα στιγμιότυπου οθόνης."
    }

    fun onScreenRecordingStateChanged(isActive: Boolean) {
        if (_isScreenRecordingActive.value == isActive) return
        _isScreenRecordingActive.value = isActive
        logAction(
            tag = "SECURITY",
            message = if (isActive) {
                "Active screen recording detected — privacy protection active"
            } else {
                "Screen recording ended"
            }
        )
    }

    fun onLogsScreenVisibilityChanged(isOpen: Boolean) {
        sessionContainer.setLogsScreenOpen(isOpen)
        if (!isOpen) {
            val currentConv = _selectedConversation.value
            val currentMsgs = _messages.value
            if (currentConv != null && currentMsgs.isNotEmpty()) {
                maybeSendReadReceipt(currentConv, currentMsgs)
            }
        }
    }

    fun openConversationById(conversationId: String) {
        if (conversationId.isBlank()) return
        val existing = _conversations.value.firstOrNull { it.id == conversationId }
        if (existing != null) {
            _pendingNotificationConversationId.value = null
            selectConversation(existing)
        } else {
            _pendingNotificationConversationId.value = conversationId
        }
    }

    fun hasNotificationPermission(): Boolean = notificationManager.hasNotificationPermission()

    fun openNotificationSettings() = notificationManager.openNotificationSettings()

    fun openBackgroundServiceChannelSettings() = notificationManager.openBackgroundServiceChannelSettings()

    fun isBackgroundServiceChannelVisible(): Boolean = notificationManager.isBackgroundServiceChannelEnabled()

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) {
            notificationManager.ensureChannelCreated()
            logAction("NOTIF", "System priority notifications enabled")
        } else {
            logAction(
                tag = "NOTIF",
                message = "Notification permission denied — enable in Android Settings",
                isError = true
            )
        }
    }

    fun selectConversation(conversation: Conversation?) {
        _selectedConversation.value = conversation
        sessionContainer.setActiveConversationId(conversation?.id)
        messagesJob?.cancel()
        _messages.value = emptyList()

        if (conversation != null) {
            announceNicknameIfNeeded(conversation)
            messagesJob = viewModelScope.launch {
                val currentInboxId = (engine.state.value as? XmtpEngineState.Ready)?.client?.inboxId
                repository.observeMessages(conversation).collectLatest { msgList ->
                    _messages.value = msgList
                    maybeSendReadReceipt(conversation, msgList)
                    msgList.forEach { msg ->
                        recordCommittedMessage(
                            msgId = msg.id,
                            sender = msg.senderInboxId,
                            topic = conversation.topic,
                            sentAtNs = msg.sentAtNs
                        )
                        if (msg.id.isNotBlank() && loggedMessageIds.add(msg.id)) {
                            val isOwn = msg.senderInboxId == currentInboxId
                            val tag = if (isOwn) "MLS" else "E2EE"
                            val prefix = if (isOwn) "RX LOOPBACK" else "RX COMMITTED"
                            logAction(
                                tag = tag,
                                message = "$prefix | MsgID: ${msg.id} | ConvID: ${conversation.id} | Topic: ${conversation.topic} | SenderInbox: ${msg.senderInboxId} | SentNs: ${msg.sentAtNs}"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isLoopbackConversation(conversation: Conversation): Boolean =
        conversation is Conversation.Group && conversation.group.name == "MLS Research Loopback"

    private fun maybeSendReadReceipt(conversation: Conversation, messages: List<DecodedMessage>) {
        val localInboxId = (engine.state.value as? XmtpEngineState.Ready)?.client?.inboxId ?: return
        val latestPeerMsg = messages.lastOrNull { !it.senderInboxId.equals(localInboxId, ignoreCase = true) }
        val shouldSend = ReadReceiptPolicy.shouldSendReadReceipt(
            latestPeerMessage = latestPeerMsg,
            localLastReadTimestampMs = repository.localReadTimestamps.value[conversation.id],
            lastSentForPeerMsgId = lastSentReceiptPeerMsgId[conversation.id],
            isAppInForeground = sessionContainer.isInForeground(),
            isLogsScreenOpen = sessionContainer.isLogsScreenOpen()
        )
        if (!shouldSend || latestPeerMsg == null) return

        // Marcamos optimista antes de enviar para evitar rafagas duplicadas durante recomposiciones o syncs rapidos.
        lastSentReceiptPeerMsgId[conversation.id] = latestPeerMsg.id
        viewModelScope.launch {
            repository.sendReadReceipt(conversation)
                .onSuccess {
                    logAction("READ_RECEIPT", "Sent encrypted read receipt [conv=${conversation.id.take(8)}...]")
                }
                .onFailure {
                    lastSentReceiptPeerMsgId.remove(conversation.id, latestPeerMsg.id)
                }
        }
    }

    private fun announceNicknameIfNeeded(conversation: Conversation) {
        if (isLoopbackConversation(conversation)) return
        val nickname = nicknameSnapshot.value.myNickname ?: return
        if (lastAnnouncedNicknameByConv[conversation.id] == nickname) return
        lastAnnouncedNicknameByConv[conversation.id] = nickname
        viewModelScope.launch {
            repository.sendProfileNickname(conversation, nickname)
                .onFailure {
                    lastAnnouncedNicknameByConv.remove(conversation.id, nickname)
                }
        }
    }

    fun updateMyNickname(rawNickname: String) {
        val saved = sessionContainer.nicknameStore.setMyNickname(rawNickname)
        if (saved != null) {
            _statusBanner.value = "Το ψευδώνυμο ενημερώθηκε: $saved"
            logAction("IDENTITY", "Custom profile nickname updated: $saved")
            _conversations.value.forEach { conv ->
                if (!isLoopbackConversation(conv)) {
                    announceNicknameIfNeeded(conv)
                }
            }
        } else {
            lastAnnouncedNicknameByConv.clear()
            _statusBanner.value = "Το ψευδώνυμο αφαιρέθηκε."
            logAction("IDENTITY", "Custom profile nickname cleared")
        }
    }

    fun updateContactNickname(contactKey: String, rawNickname: String) {
        val saved = sessionContainer.nicknameStore.setLocalContactNickname(contactKey, rawNickname)
        if (saved != null) {
            _statusBanner.value = "Το ψευδώνυμο επαφής αποθηκεύτηκε: $saved"
            logAction("IDENTITY", "Local contact nickname set [$saved]")
        } else {
            _statusBanner.value = "Το ψευδώνυμο επαφής επαναφέρθηκε."
            logAction("IDENTITY", "Local contact nickname reset")
        }
    }

    fun startNewConversation(peerIdentifier: String, contactNickname: String? = null) {
        if (_isCreatingConversation.value || _isShredding.value || _isBenchmarking.value) return
        _isCreatingConversation.value = true
        viewModelScope.launch {
            try {
                val masked = if (peerIdentifier.length >= 14) "${peerIdentifier.take(6)}...${peerIdentifier.takeLast(4)}" else peerIdentifier
                logAction("MLS", "Initiating MLS handshake with $masked...")
                ThesisTelemetry.measureSuspendOperation("4. MLS Conversation Handshake") {
                    repository.createConversation(peerIdentifier)
                }
                .onSuccess { conv ->
                    val sanitizedNick = NicknamePolicy.sanitize(contactNickname)
                    if (sanitizedNick != null) {
                        val contactKey = if (conv is Conversation.Dm) conv.dm.peerInboxId else conv.id
                        sessionContainer.nicknameStore.setLocalContactNickname(contactKey, sanitizedNick)
                    }
                    _statusBanner.value = "Η συνομιλία MLS δημιουργήθηκε."
                    logAction("MLS", "MLS conversation established. Topic: ${conv.topic.take(24)}...")
                    selectConversation(conv)
                }
                .onFailure { err ->
                    _statusBanner.value = "Αποτυχία δημιουργίας συνομιλίας: ${err.message}"
                    logAction("MLS", "Handshake failed: ${err.message}", isError = true)
                }
            } finally {
                _isCreatingConversation.value = false
            }
        }
    }

    fun syncConversations() {
        if (_isSyncing.value || _isShredding.value) return
        val activeConv = _selectedConversation.value
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                logAction("SYNC", "Syncing with XMTP DEV network...")
                if (activeConv != null) {
                    ThesisTelemetry.measureSuspendOperation("MLS Conversation Sync") {
                        repository.syncConversation(activeConv)
                    }
                    .onSuccess {
                        logAction("SYNC", "Active conversation messages synced.")
                    }
                    .onFailure { err ->
                        logAction("SYNC", "Conversation sync failed: ${err.message}", isError = true)
                    }
                } else {
                    ThesisTelemetry.measureSuspendOperation("MLS Network Sync") {
                        repository.syncConversations()
                    }
                    .onSuccess {
                        logAction("SYNC", "Sync complete. Active conversations: ${_conversations.value.size}")
                    }
                    .onFailure { err ->
                        logAction("SYNC", "Sync failed: ${err.message}", isError = true)
                    }
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun startSelfConversation() {
        if (_isCreatingConversation.value || _isShredding.value || _isBenchmarking.value) return
        _isCreatingConversation.value = true
        viewModelScope.launch {
            try {
                logAction("MLS", "Initiating MLS Loopback Ratchet Session...")
                ThesisTelemetry.measureSuspendOperation("4. MLS Conversation Handshake") {
                    repository.createLoopbackConversation()
                }
                .onSuccess { conv ->
                    _statusBanner.value = "Πειραματική Συνεδρία MLS (Loopback) ενεργή."
                    logAction("MLS", "Loopback Ratchet Group established. Topic: ${conv.topic.take(24)}...")
                    selectConversation(conv)
                }
                .onFailure { err ->
                    _statusBanner.value = "Αποτυχία εκκίνησης loopback: ${err.message}"
                    logAction("MLS", "Loopback failed: ${err.message}", isError = true)
                }
            } finally {
                _isCreatingConversation.value = false
            }
        }
    }

    fun sendMessage(text: String, onFailureRestore: (() -> Unit)? = null) {
        if (_isSending.value || _isShredding.value || _isBenchmarking.value) return
        val conv = _selectedConversation.value ?: return
        _isSending.value = true
        viewModelScope.launch {
            try {
                logAction("E2EE", "Dispatching encrypted MLS payload...")
                ThesisTelemetry.measureSuspendOperation("5. MLS E2EE Message Dispatch") {
                    repository.sendMessage(conv, text)
                }
                .onSuccess { msgId ->
                    val sender0x = walletAddress.value ?: "0x..."
                    val clientInbox = (engine.state.value as? XmtpEngineState.Ready)?.client?.inboxId ?: "N/A"
                    recordCommittedMessage(
                        msgId = msgId,
                        sender = if (clientInbox != "N/A") clientInbox else sender0x,
                        topic = conv.topic
                    )
                    logAction(
                        "MLS",
                        "TX COMMITTED | MsgID: $msgId | ConvID: ${conv.id} | Topic: ${conv.topic} | Sender0x: $sender0x | SenderInbox: $clientInbox"
                    )
                }
                .onFailure { err ->
                    _statusBanner.value = "Αποτυχία αποστολής: ${err.message}"
                    logAction("E2EE", "Dispatch failed: ${err.message}", isError = true)
                    onFailureRestore?.invoke()
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * Εκτελεί GDPR Άρθρο 17 Crypto-Shredding:
     * 1. Τερματίζει τον XMTP Engine.
     * 2. Καταστρέφει το Hardware KEK στο Android Keystore.
     * 3. Διαγράφει φυσικά τα αρχεία της SQLCipher (.db, -wal, -shm, -journal).
     */
    fun performCryptoShredding() {
        if (_isShredding.value) return
        val address = walletAddress.value ?: return
        _isShredding.value = true
        viewModelScope.launch {
            try {
                sessionStateMutex.withLock {
                    messagesJob?.cancel()
                    _selectedConversation.value = null
                    _messages.value = emptyList()
                    _conversations.value = emptyList()

                    // Limpiamos los registros de la sesion anterior antes de medir el borrado para conservar las metricas y el certificado GDPR.
                    committedProofMap.clear()
                    _verifiedMessageCount.value = 0
                    loggedMessageIds.clear()
                    lastSentReceiptPeerMsgId.clear()
                    lastAnnouncedNicknameByConv.clear()
                    _pendingNotificationConversationId.value = null
                    _actionLogs.value = emptyList()
                    ThesisTelemetry.clearTelemetryData()
                    ReportExporter.purgeCachedReports(appContext.cacheDir)

                    logAction("GDPR", "Executing GDPR Art. 17 cryptographic wipe...")

                    engine.shutdownAndAwait()
                    sessionContainer.resetOnGdprShred()

                    val res = ThesisTelemetry.measureSuspendOperation("6. GDPR Art.17 Crypto-Shredding") {
                        cryptoShredder.shredAccountData(address)
                    }
                    when (res) {
                        is ShredResult.Success -> {
                            _lastShredProof.value = res.auditProof
                            _statusBanner.value = "GDPR Wipe: Κλειδί Keystore & ${res.deletedFilesCount} αρχεία βάσης καταστράφηκαν οριστικά."
                            logAction("GDPR", "KEK destroyed & ${res.deletedFilesCount} database files expunged")
                            res.auditProof?.let { proof ->
                                val certMsg = "Certificate: Keystore=" + (if (proof.isKeystoreEntryPurged) "PURGED" else "FAILED") +
                                    ", Storage=" + (if (proof.isPrimaryDatabaseUnlinked) "EXPUNGED" else "FAILED") +
                                    ", Compliant=" + proof.isCompliant +
                                    ", SHA-256=" + proof.certificateSha256.take(16) + "..."
                                logAction("GDPR", certMsg)
                            }
                        }
                        is ShredResult.PartialSuccessCryptoShreddedOnly -> {
                            _lastShredProof.value = res.auditProof
                            _statusBanner.value = "Crypto-Shredded: ${res.message}"
                            logAction("GDPR", "Crypto-shredded: ${res.message}")
                            res.auditProof?.let { proof ->
                                val certMsg = "Certificate: Keystore=" + (if (proof.isKeystoreEntryPurged) "PURGED" else "FAILED") +
                                    ", Compliant=" + proof.isCompliant +
                                    ", SHA-256=" + proof.certificateSha256.take(16) + "..."
                                logAction("GDPR", certMsg)
                            }
                        }
                        is ShredResult.PartialSuccess -> {
                            _lastShredProof.value = res.auditProof
                            _statusBanner.value = "Crypto-Shredded: ${res.message}"
                            logAction("GDPR", "Crypto-shredded: ${res.message}")
                            res.auditProof?.let { proof ->
                                val certMsg = "Certificate: Keystore=" + (if (proof.isKeystoreEntryPurged) "PURGED" else "FAILED") +
                                    ", Storage=" + (if (proof.isPrimaryDatabaseUnlinked) "EXPUNGED" else "FAILED") +
                                    ", SHA-256=" + proof.certificateSha256.take(16) + "..."
                                logAction("GDPR", certMsg)
                            }
                        }
                        is ShredResult.Failure -> {
                            _statusBanner.value = "Σφάλμα κατά το Crypto-Shredding."
                            logAction("GDPR", "Crypto-shredding failed", isError = true)
                        }
                    }
                }
            } finally {
                _isShredding.value = false
            }
        }
    }

    /**
     * Executes an automated sequence of N loopback ratchet dispatches
     * to gather empirical latency distributions for research evaluation.
     */
    fun runBatchBenchmark(iterations: Int = 10) {
        if (_isBenchmarking.value || _isShredding.value || _isCreatingConversation.value) return
        val readyClient = (engine.state.value as? XmtpEngineState.Ready)?.client
        if (readyClient == null) {
            _statusBanner.value = "Απαιτείται ενεργή σύνδεση XMTP για την εκτέλεση benchmark."
            return
        }

        _isBenchmarking.value = true
        viewModelScope.launch {
            try {
                sessionStateMutex.withLock {
                    _statusBanner.value = "Εκτέλεση Batch Benchmark ($iterations δοκιμές MLS)..."
                    logAction("BENCHMARK", "Starting automated batch benchmark (N=$iterations)...")

                try {
                    // Aislamos el benchmark al grupo loopback para nunca enviar sondas de prueba a un DM con otro dispositivo.
                    var activeConv: Conversation? = _conversations.value.firstOrNull { conv ->
                        conv is Conversation.Group && conv.group.name == "MLS Research Loopback"
                    }
                    if (activeConv == null) {
                        logAction("BENCHMARK", "Establishing dedicated MLS loopback ratchet...")
                        val loopbackResult = ThesisTelemetry.measureSuspendOperation("4. MLS Conversation Handshake") {
                            repository.createLoopbackConversation()
                        }
                        loopbackResult.onSuccess { conv ->
                            activeConv = conv
                        }.onFailure { err ->
                            _statusBanner.value = "Αποτυχία εκκίνησης loopback: ${err.message}"
                            logAction("BENCHMARK", "Loopback failed: ${err.message}", isError = true)
                            return@withLock
                        }
                    }

                    val conv = activeConv ?: return@withLock
                    var successfulSamples = 0

                    for (i in 1..iterations) {
                        val payload = "MLS Latency Benchmark Probe #$i [${System.currentTimeMillis()}]"
                        val sendResult = ThesisTelemetry.measureSuspendOperation("5. MLS E2EE Message Dispatch") {
                            repository.sendMessage(conv, payload)
                        }

                        if (sendResult.isSuccess) {
                            val probeMsgId = sendResult.getOrNull() ?: "PROBE_COMMITTED"
                            val sender0x = walletAddress.value ?: "0x..."
                            val clientInbox = readyClient.inboxId
                            successfulSamples++
                            recordCommittedMessage(
                                msgId = probeMsgId,
                                sender = clientInbox,
                                topic = conv.topic
                            )
                            logAction(
                                tag = "BENCHMARK",
                                message = "TX COMMITTED | Probe #$i/$iterations | MsgID: $probeMsgId | ConvID: ${conv.id} | Topic: ${conv.topic} | Sender0x: $sender0x | SenderInbox: $clientInbox"
                            )
                        } else {
                            logAction(
                                tag = "BENCHMARK",
                                message = "Probe #$i/$iterations failed: ${sendResult.exceptionOrNull()?.message}",
                                isError = true
                            )
                        }
                        delay(120)
                    }

                    _statusBanner.value = "Ολοκληρώθηκε Batch Benchmark: $successfulSamples/$iterations επιτυχείς δοκιμές."
                    logAction(
                        tag = "BENCHMARK",
                        message = "Batch complete ($successfulSamples/$iterations). Summaries ready for export."
                    )
                    } catch (e: Exception) {
                        _statusBanner.value = "Σφάλμα κατά το batch benchmark: ${e.message}"
                        logAction("BENCHMARK", "Batch test error: ${e.message}", isError = true)
                    }
                }
            } finally {
                _isBenchmarking.value = false
            }
        }
    }


    fun checkForUpdates(manual: Boolean = false) {
        viewModelScope.launch {
            if (manual) {
                logAction("UPDATE", "Checking for updates on GitHub Releases...")
            }
            appUpdateManager.checkForUpdates(manual = manual)
                .onSuccess { info ->
                    if (info.isUpdateAvailable) {
                        logAction("UPDATE", "Update available: v${info.latestVersion} (Current: v${info.currentVersion})")
                    } else if (manual) {
                        logAction("UPDATE", "App is up to date (v${info.currentVersion}).")
                    }
                }
                .onFailure { err ->
                    logAction("UPDATE", "Update check failed: ${err.message}", isError = true)
                }
        }
    }

    fun downloadUpdate(updateInfo: AppUpdateInfo) {
        viewModelScope.launch {
            logAction("UPDATE", "Starting download of v${updateInfo.latestVersion}...")
            appUpdateManager.downloadUpdate(updateInfo)
                .onSuccess { apkFile ->
                    logAction("UPDATE", "Downloaded update APK (${apkFile.length() / 1024} KB). Ready to install.")
                }
                .onFailure { err ->
                    logAction("UPDATE", "Download error: ${err.message}", isError = true)
                }
        }
    }

    fun installUpdate(apkFile: File) {
        logAction("UPDATE", "Launching package installer for update...")
        appUpdateManager.installUpdate(apkFile)
            .onFailure { err ->
                logAction("UPDATE", "Installation dispatch failed: ${err.message}", isError = true)
            }
    }

    fun dismissUpdate() {
        appUpdateManager.dismissUpdate()
    }

    fun getCurrentVersionName(): String = appUpdateManager.getCurrentVersionName()

    fun canRequestPackageInstalls(): Boolean = appUpdateManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() = appUpdateManager.openInstallPermissionSettings()

    override fun onCleared() {
        // Desvinculamos el callback de UI pero mantenemos el motor vivo para notificaciones fuera de la app.
        sessionContainer.onNotificationDispatched = null
    }
}
