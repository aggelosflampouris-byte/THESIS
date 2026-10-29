package com.example.thesis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.repository.ConversationRepository
import com.example.thesis.updater.AppUpdateInfo
import com.example.thesis.updater.AppUpdateManager
import com.example.thesis.updater.UpdateStatus
import java.io.File
import org.xmtp.android.library.libxmtp.DecodedMessage
import com.example.thesis.security.CryptoShredder
import com.example.thesis.security.KeystoreManager
import com.example.thesis.security.ShredResult
import com.example.thesis.security.ThesisTelemetry
import com.example.thesis.xmtp.XmtpEngine
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.XMTPEnvironment


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
    private val keystoreManager = KeystoreManager(appContext)
    private val databaseFactory = DatabaseFactory(appContext)
    private val cryptoShredder = CryptoShredder(appContext, keystoreManager, databaseFactory)
    private val engine = XmtpEngine(appContext, XMTPEnvironment.DEV)
    private val repository = ConversationRepository(engine, viewModelScope)
    val appUpdateManager = AppUpdateManager(appContext)
    val updateStatus: StateFlow<UpdateStatus> = appUpdateManager.updateStatus

    private val sessionStateMutex = Mutex()
    private var activeSigner: ThesisSigner? = null
    private var messagesJob: Job? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _isShredding = MutableStateFlow(false)
    val isShredding: StateFlow<Boolean> = _isShredding.asStateFlow()

    val engineState: StateFlow<XmtpEngineState> = engine.state

    private val _walletAddress = MutableStateFlow<String?>(null)
    val walletAddress: StateFlow<String?> = _walletAddress.asStateFlow()

    val inboxId: StateFlow<String?> = engine.state
        .map { (it as? XmtpEngineState.Ready)?.client?.inboxId }
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

    fun logAction(tag: String, message: String, durationMs: Double? = null, isError: Boolean = false) {
        val entry = ActionLogEntry(
            tag = tag,
            message = message,
            durationMs = durationMs,
            isError = isError
        )
        _actionLogs.value = (_actionLogs.value + entry).takeLast(100)
    }

    fun clearActionLogs() {
        _actionLogs.value = emptyList()
        ThesisTelemetry.clearTelemetryData()
    }

    fun exportBenchmarkReport(): String = ThesisTelemetry.exportFullReport()

    fun exportBenchmarkCsv(): String = ThesisTelemetry.exportAsCsv()

    init {
        viewModelScope.launch {
            repository.observeConversations().collectLatest { list ->
                _conversations.value = list
            }
        }
        viewModelScope.launch {
            ThesisTelemetry.eventsFlow.collect { event ->
                logAction(
                    tag = "METRIC",
                    message = event.operationName,
                    durationMs = event.durationMs,
                    isError = !event.isSuccess
                )
            }
        }
        checkForUpdates(manual = false)
    }

    /**
     * Εκκινεί την αλυσίδα ασφαλείας: Hardware DEK -> SQLCipher Sandbox -> EIP-191 Signer -> XMTP MLS.
     */
    fun initializeSession() {
        viewModelScope.launch {
            sessionStateMutex.withLock {
                if (engine.state.value is XmtpEngineState.Ready) {
                    logAction("SESSION", "Session already active, skipping re-initialization.")
                    return@withLock
                }
                _isInitializing.value = true
                try {
                    logAction("KEYSTORE", "Requesting hardware DEK unwrap (AES-256-GCM)...")
                    val dekResult = ThesisTelemetry.measureOperation("1. Keystore AES-256-GCM DEK Unwrap") {
                        keystoreManager.getOrCreateDatabaseKey()
                    }
                    val dekBytes = try {
                        dekResult.getOrThrow()
                    } catch (e: Throwable) {
                        _statusBanner.value = "Σφάλμα Hardware Keystore: ${e.message}"
                        logAction("KEYSTORE", "Hardware Keystore failure: ${e.message}", isError = true)
                        return@withLock
                    }

                    try {
                        val signer = ThesisTelemetry.measureOperation("2. Secp256k1 Ephemeral KeyGen") {
                            activeSigner ?: ThesisSigner.createEphemeral().also { activeSigner = it }
                        }
                        _walletAddress.value = signer.address
                        val maskedAddress = if (signer.address.length >= 10) {
                            "${signer.address.take(6)}...${signer.address.takeLast(4)}"
                        } else signer.address
                        logAction("IDENTITY", "Ephemeral SECP256K1 signer active: $maskedAddress")

                        val dbFile = databaseFactory.resolveDatabaseFile(signer.address)
                        val dbDirectory = dbFile.parentFile ?: appContext.filesDir

                        ThesisTelemetry.measureSuspendOperation("3. SQLCipher Sandbox & MLS Init") {
                            engine.initialise(
                                signer = signer,
                                dbDirectory = dbDirectory,
                                dbEncryptionKey = dekBytes.copyOf()
                            )
                        }
                        _statusBanner.value = "Σύνδεση με Hardware-Backed SQLCipher & MLS..."
                        logAction("XMTP", "Connected to XMTP v4 MLS network with SQLCipher storage")
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

    fun selectConversation(conversation: Conversation?) {
        _selectedConversation.value = conversation
        messagesJob?.cancel()
        _messages.value = emptyList()

        if (conversation != null) {
            messagesJob = viewModelScope.launch {
                repository.observeMessages(conversation).collectLatest { msgList ->
                    _messages.value = msgList
                }
            }
        }
    }

    fun startNewConversation(peerIdentifier: String) {
        viewModelScope.launch {
            val masked = if (peerIdentifier.length >= 14) "${peerIdentifier.take(6)}...${peerIdentifier.takeLast(4)}" else peerIdentifier
            logAction("MLS", "Initiating MLS handshake with $masked...")
            ThesisTelemetry.measureSuspendOperation("4. MLS Conversation Handshake") {
                repository.createConversation(peerIdentifier)
            }
            .onSuccess { conv ->
                _statusBanner.value = "Η συνομιλία MLS δημιουργήθηκε."
                logAction("MLS", "MLS conversation established. Topic: ${conv.topic.take(24)}...")
                selectConversation(conv)
            }
            .onFailure { err ->
                _statusBanner.value = "Αποτυχία δημιουργίας συνομιλίας: ${err.message}"
                logAction("MLS", "Handshake failed: ${err.message}", isError = true)
            }
        }
    }

    fun syncConversations() {
        val activeConv = _selectedConversation.value
        viewModelScope.launch {
            _isSyncing.value = true
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
            _isSyncing.value = false
        }
    }

    fun startSelfConversation() {
        viewModelScope.launch {
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
        }
    }

    fun sendMessage(text: String) {
        val conv = _selectedConversation.value ?: return
        viewModelScope.launch {
            _isSending.value = true
            try {
                logAction("E2EE", "Dispatching encrypted MLS payload...")
                ThesisTelemetry.measureSuspendOperation("5. MLS E2EE Message Dispatch") {
                    repository.sendMessage(conv, text)
                }
                .onSuccess { msgId ->
                    logAction("E2EE", "Message committed to XMTP ledger (ID: ${msgId.take(16)}...)")
                }
                .onFailure { err ->
                    _statusBanner.value = "Αποτυχία αποστολής: ${err.message}"
                    logAction("E2EE", "Dispatch failed: ${err.message}", isError = true)
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
        val address = _walletAddress.value ?: return
        viewModelScope.launch {
            sessionStateMutex.withLock {
                _isShredding.value = true
                try {
                    logAction("GDPR", "Executing GDPR Art. 17 cryptographic wipe...")
                    messagesJob?.cancel()
                    _selectedConversation.value = null
                    _messages.value = emptyList()
                    _conversations.value = emptyList()

                    engine.shutdown()
                    activeSigner?.close()
                    activeSigner = null

                    val res = ThesisTelemetry.measureSuspendOperation("6. GDPR Art.17 Crypto-Shredding") {
                        cryptoShredder.shredAccountData(address)
                    }
                    when (res) {
                        is ShredResult.Success -> {
                            _walletAddress.value = null
                            _statusBanner.value = "GDPR Wipe: Κλειδί Keystore & ${res.deletedFilesCount} αρχεία βάσης καταστράφηκαν οριστικά."
                            logAction("GDPR", "KEK destroyed & ${res.deletedFilesCount} database files expunged")
                            res.auditProof?.let { proof ->
                                val certMsg = "Certificate: Keystore=" + (if (proof.isKeystoreEntryPurged) "PURGED" else "FAILED") +
                                    ", Storage=" + (if (proof.isPrimaryDatabaseUnlinked) "EXPUNGED" else "FAILED") +
                                    ", Compliant=" + proof.isCompliant
                                logAction("GDPR", certMsg)
                            }
                        }
                        is ShredResult.PartialSuccessCryptoShreddedOnly -> {
                            _walletAddress.value = null
                            _statusBanner.value = "Crypto-Shredded: ${res.message}"
                            logAction("GDPR", "Crypto-shredded: ${res.message}")
                            res.auditProof?.let { proof ->
                                val certMsg = "Certificate: Keystore=" + (if (proof.isKeystoreEntryPurged) "PURGED" else "FAILED") +
                                    ", Compliant=" + proof.isCompliant
                                logAction("GDPR", certMsg)
                            }
                        }
                        is ShredResult.PartialSuccess -> {
                            _walletAddress.value = null
                            _statusBanner.value = "Crypto-Shredded: ${res.message}"
                            logAction("GDPR", "Crypto-shredded: ${res.message}")
                            res.auditProof?.let { proof ->
                                val certMsg = "Certificate: Keystore=" + (if (proof.isKeystoreEntryPurged) "PURGED" else "FAILED") +
                                    ", Storage=" + (if (proof.isPrimaryDatabaseUnlinked) "EXPUNGED" else "FAILED")
                                logAction("GDPR", certMsg)
                            }
                        }
                        is ShredResult.Failure -> {
                            _statusBanner.value = "Σφάλμα κατά το Crypto-Shredding."
                            logAction("GDPR", "Crypto-shredding failed", isError = true)
                        }
                    }
                } finally {
                    _isShredding.value = false
                }
            }
        }
    }

    /**
     * Executes an automated sequence of N loopback ratchet dispatches
     * to gather empirical latency distributions for research evaluation.
     */
    fun runBatchBenchmark(iterations: Int = 10) {
        if (_isBenchmarking.value) return
        val readyClient = (engine.state.value as? XmtpEngineState.Ready)?.client
        if (readyClient == null) {
            _statusBanner.value = "Απαιτείται ενεργή σύνδεση XMTP για την εκτέλεση benchmark."
            return
        }

        viewModelScope.launch {
            sessionStateMutex.withLock {
                _isBenchmarking.value = true
                _statusBanner.value = "Εκτέλεση Batch Benchmark ($iterations δοκιμές MLS)..."
                logAction("BENCHMARK", "Starting automated batch benchmark (N=$iterations)...")

                try {
                    var activeConv = _selectedConversation.value
                    if (activeConv == null) {
                        logAction("BENCHMARK", "No active conversation; establishing loopback ratchet...")
                        val loopbackResult = ThesisTelemetry.measureSuspendOperation("4. MLS Conversation Handshake") {
                            repository.createLoopbackConversation()
                        }
                        loopbackResult.onSuccess { conv ->
                            activeConv = conv
                            selectConversation(conv)
                        }.onFailure { err ->
                            _statusBanner.value = "Αποτυχία εκκίνησης loopback: ${err.message}"
                            logAction("BENCHMARK", "Loopback failed: ${err.message}", isError = true)
                            return@withLock
                        }
                    }

                    val conv = activeConv ?: return@withLock
                    var successfulSamples = 0

                    for (i in 1..iterations) {
                        val payload = "⚡ MLS Latency Benchmark Probe #$i [${System.currentTimeMillis()}]"
                        val sendResult = ThesisTelemetry.measureSuspendOperation("5. MLS E2EE Message Dispatch") {
                            repository.sendMessage(conv, payload)
                        }

                        if (sendResult.isSuccess) {
                            successfulSamples++
                            logAction(
                                tag = "BENCHMARK",
                                message = "Probe #$i/$iterations dispatched successfully"
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
                } finally {
                    _isBenchmarking.value = false
                }
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

    override fun onCleared() {
        engine.shutdown()
        repository.close()
        activeSigner?.close()
    }
}
