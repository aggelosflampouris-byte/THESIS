package com.example.thesis.notifications

import android.content.Context
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.repository.ConversationRepository
import com.example.thesis.security.CryptoShredder
import com.example.thesis.security.KeystoreManager
import com.example.thesis.updater.AppUpdateManager
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.XMTPEnvironment
import org.xmtp.android.library.libxmtp.DecodedMessage
import java.util.Collections

internal class ThesisSessionContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val keystoreManager = KeystoreManager(appContext)
    val databaseFactory = DatabaseFactory(appContext)
    val cryptoShredder = CryptoShredder(appContext, keystoreManager, databaseFactory)
    val engine = XmtpEngine(appContext, XMTPEnvironment.DEV)

    // El alcance de aplicacion mantiene viva la escucha gRPC y las notificaciones aunque se cierre la UI.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository = ConversationRepository(engine, appScope)
    val appUpdateManager = AppUpdateManager(appContext)
    val notificationManager = MessageNotificationManager(appContext)
    val sessionMutex = Mutex()

    @Volatile
    var activeSigner: ThesisSigner? = null

    private val _walletAddress = MutableStateFlow<String?>(null)
    val walletAddress: StateFlow<String?> = _walletAddress.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    private val notifiedMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var isAppInForeground: Boolean = false

    @Volatile
    private var isLogsScreenOpen: Boolean = false

    @Volatile
    private var activeConversationId: String? = null

    @Volatile
    private var conversationsSnapshot: List<Conversation> = emptyList()

    @Volatile
    var onNotificationDispatched: ((String) -> Unit)? = null

    init {
        notificationManager.ensureChannelCreated()
        appScope.launch {
            repository.incomingMessages.collect { incoming ->
                handleIncomingMessage(incoming)
            }
        }
    }

    fun setWalletAddress(address: String?) {
        _walletAddress.value = address
    }

    fun isInForeground(): Boolean = isAppInForeground

    fun setAppInForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground && !isLogsScreenOpen) {
            activeConversationId?.let { clearUnread(it) }
        }
    }

    fun setLogsScreenOpen(isOpen: Boolean) {
        isLogsScreenOpen = isOpen
        if (!isOpen && isAppInForeground) {
            activeConversationId?.let { clearUnread(it) }
        }
    }

    fun setActiveConversationId(conversationId: String?) {
        activeConversationId = conversationId
        if (conversationId != null) {
            clearUnread(conversationId)
        }
    }

    fun updateConversationsSnapshot(list: List<Conversation>) {
        conversationsSnapshot = list
    }

    fun clearUnread(conversationId: String) {
        if (conversationId.isBlank()) return
        if (_unreadCounts.value.containsKey(conversationId)) {
            _unreadCounts.value = _unreadCounts.value - conversationId
        }
        notificationManager.cancelConversationNotification(conversationId)
    }

    fun resetOnGdprShred() {
        notifiedMessageIds.clear()
        _unreadCounts.value = emptyMap()
        conversationsSnapshot = emptyList()
        activeConversationId = null
        repository.resetNotificationBaseline()
        XmtpMessagingService.stop(appContext)
        notificationManager.cancelAllNotifications()
        _walletAddress.value = null
        activeSigner?.close()
        activeSigner = null
    }

    suspend fun ensureSessionConnectedForBackground() {
        sessionMutex.withLock {
            if (engine.state.value is XmtpEngineState.Ready) return
            if (!keystoreManager.hasPersistedSession()) return

            val dekBytes = withContext(Dispatchers.IO) {
                keystoreManager.getOrCreateDatabaseKey().getOrNull()
            } ?: return

            try {
                val privKeyBytes = withContext(Dispatchers.IO) {
                    keystoreManager.getOrCreateIdentityPrivateKey().getOrNull()
                } ?: return

                val signer = try {
                    activeSigner ?: withContext(Dispatchers.Default) {
                        ThesisSigner.fromPrivateKey(privKeyBytes)
                    }.also { activeSigner = it }
                } finally {
                    privKeyBytes.fill(0x00)
                }

                _walletAddress.value = signer.address
                val dbDirectory = withContext(Dispatchers.IO) {
                    databaseFactory.resolveAccountDatabaseDirectory(signer.address)
                }
                val state = engine.initialiseAndAwait(
                    signer = signer,
                    dbDirectory = dbDirectory,
                    dbEncryptionKey = dekBytes
                )
                if (state is XmtpEngineState.Ready) {
                    repository.pollIncomingMessages(seedOnly = true)
                }
            } finally {
                dekBytes.fill(0x00)
            }
        }
    }

    private fun handleIncomingMessage(msg: DecodedMessage) {
        val localInboxId = (engine.state.value as? XmtpEngineState.Ready)?.client?.inboxId
        val alreadyNotified = synchronized(notifiedMessageIds) { msg.id in notifiedMessageIds }
        val shouldNotify = MessageNotificationPolicy.shouldNotify(
            messageId = msg.id,
            conversationId = msg.conversationId,
            senderInboxId = msg.senderInboxId,
            localInboxId = localInboxId,
            body = msg.body,
            isAppInForeground = isAppInForeground,
            activeConversationId = activeConversationId,
            isLogsScreenOpen = isLogsScreenOpen,
            alreadyNotified = alreadyNotified
        )
        if (!shouldNotify) return

        markMessageNotified(msg.id)
        val currentMap = _unreadCounts.value
        val nextCount = (currentMap[msg.conversationId] ?: 0) + 1
        _unreadCounts.value = currentMap + (msg.conversationId to nextCount)

        val conv = conversationsSnapshot.firstOrNull { it.id == msg.conversationId }
        val explicitName = when (conv) {
            is Conversation.Group -> {
                val name = conv.group.name
                when {
                    name == "MLS Research Loopback" -> "Loopback MLS"
                    name.isNotBlank() -> name
                    else -> null
                }
            }
            else -> null
        }
        val title = MessageNotificationPolicy.formatSenderTitle(explicitName, msg.senderInboxId)
        val bodyPreview = MessageNotificationPolicy.formatBodyPreview(msg.body)
        val sentAtMs = msg.sentAt?.time
            ?: if (msg.sentAtNs > 0L) msg.sentAtNs / 1_000_000L else System.currentTimeMillis()

        val posted = notificationManager.showIncomingMessageNotification(
            conversationId = msg.conversationId,
            title = title,
            bodyPreview = bodyPreview,
            timestampMs = sentAtMs
        )
        if (posted) {
            onNotificationDispatched?.invoke(msg.conversationId)
        }
    }

    private fun markMessageNotified(messageId: String) {
        synchronized(notifiedMessageIds) {
            if (notifiedMessageIds.size >= 512) {
                val oldest = notifiedMessageIds.firstOrNull()
                if (oldest != null) {
                    notifiedMessageIds.remove(oldest)
                }
            }
            notifiedMessageIds.add(messageId)
        }
    }
}
