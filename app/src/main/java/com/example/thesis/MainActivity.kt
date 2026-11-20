package com.example.thesis

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.thesis.ui.ChatViewModel
import com.example.thesis.ui.components.MessagingTopBar
import com.example.thesis.ui.components.QrDownloadFullDialog
import com.example.thesis.ui.components.UpdateAvailableBanner
import com.example.thesis.ui.components.messagingStatusText
import com.example.thesis.ui.dialogs.AppUpdateDialog
import com.example.thesis.ui.dialogs.GDPRShredConfirmationDialog
import com.example.thesis.ui.dialogs.IdentityDetailsDialog
import com.example.thesis.ui.dialogs.MLSProtocolInspectorDialog
import com.example.thesis.ui.dialogs.NewConversationDialog
import com.example.thesis.ui.screens.InboxDashboardScreen
import com.example.thesis.ui.screens.LogsTelemetryScreen
import com.example.thesis.ui.screens.MessagingChatScreen
import com.example.thesis.ui.screens.MessagingConnectionError
import com.example.thesis.ui.screens.MessagingLoadingScreen
import com.example.thesis.ui.screens.MessagingStartScreen
import com.example.thesis.ui.theme.ThesisTheme
import com.example.thesis.ui.util.ReportExporter
import com.example.thesis.ui.util.copyToClipboard
import com.example.thesis.updater.UpdateConfig
import com.example.thesis.updater.UpdateStatus
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThesisTheme {
                ThesisMessagingApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun ThesisMessagingApp(viewModel: ChatViewModel) {
    val engineState by viewModel.engineState.collectAsState()
    val walletAddress by viewModel.walletAddress.collectAsState()
    val inboxId by viewModel.inboxId.collectAsState()
    val installationId by viewModel.installationId.collectAsState()
    val verifiedMsgCount by viewModel.verifiedMessageCount.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val selectedConversation by viewModel.selectedConversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val statusBanner by viewModel.statusBanner.collectAsState()
    val actionLogs by viewModel.actionLogs.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isBenchmarking by viewModel.isBenchmarking.collectAsState()
    val isCreatingConversation by viewModel.isCreatingConversation.collectAsState()
    val isShredding by viewModel.isShredding.collectAsState()
    val lastShredProof by viewModel.lastShredProof.collectAsState()

    val updateStatus by viewModel.updateStatus.collectAsState()
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showNewConversationDialog by rememberSaveable { mutableStateOf(false) }
    var showIdentityDialog by rememberSaveable { mutableStateOf(false) }
    var showShredConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showLogsScreen by rememberSaveable { mutableStateOf(false) }
    var showProtocolInspectorDialog by rememberSaveable { mutableStateOf(false) }
    var showQrDownloadDialog by rememberSaveable { mutableStateOf(false) }

    var draft by rememberSaveable(selectedConversation?.id) { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(updateStatus) {
        when (val status = updateStatus) {
            is UpdateStatus.UpdateAvailable -> showUpdateDialog = true
            is UpdateStatus.UpToDate -> {
                snackbarHostState.showSnackbar("Η εφαρμογή είναι ενημερωμένη (v${status.currentVersion})")
                viewModel.dismissUpdate()
            }
            else -> {}
        }
    }

    val onExportLogs: () -> Unit = {
        val report = viewModel.exportBenchmarkReport()
        copyToClipboard(context, "Benchmark Report", report, sensitive = false)
        scope.launch {
            snackbarHostState.showSnackbar("Η αναφορά αντιγράφηκε στο πρόχειρο (Markdown & CSV)")
        }
    }

    val onShareReport: () -> Unit = {
        val report = viewModel.exportBenchmarkReport()
        ReportExporter.shareMarkdownReport(context, report)
            .onSuccess {
                scope.launch {
                    snackbarHostState.showSnackbar("Έτοιμη κοινοποίηση αρχείου αναφοράς (.md)")
                }
            }
            .onFailure {
                scope.launch {
                    snackbarHostState.showSnackbar("Αποτυχία εξαγωγής αρχείου αναφοράς")
                }
            }
    }

    val onCopyDownloadUrl: () -> Unit = {
        copyToClipboard(
            context = context,
            label = "Latest APK URL",
            text = UpdateConfig.LATEST_APK_DOWNLOAD_URL,
            sensitive = false
        )
        scope.launch {
            snackbarHostState.showSnackbar("Ο σύνδεσμος λήψης αντιγράφηκε στο πρόχειρο")
        }
    }

    val onOpenDownloadUrl: () -> Unit = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.LATEST_APK_DOWNLOAD_URL))
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) {
            scope.launch {
                snackbarHostState.showSnackbar("Δεν βρέθηκε πρόγραμμα περιήγησης για άνοιγμα του συνδέσμου")
            }
        }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { message ->
            snackbarHostState.showSnackbar(messagingStatusText(message))
            viewModel.consumeStatusBanner()
        }
    }

    BackHandler(enabled = showLogsScreen) {
        showLogsScreen = false
    }

    BackHandler(enabled = !showLogsScreen && selectedConversation != null) {
        viewModel.selectConversation(null)
    }

    if (showNewConversationDialog) {
        NewConversationDialog(
            onDismiss = { showNewConversationDialog = false },
            onStartConversation = { target ->
                showNewConversationDialog = false
                viewModel.startNewConversation(target)
            }
        )
    }

    if (showIdentityDialog) {
        IdentityDetailsDialog(
            address = walletAddress,
            inboxId = inboxId,
            currentVersion = viewModel.getCurrentVersionName(),
            isCheckingUpdate = updateStatus is UpdateStatus.Checking,
            onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
            onCopyAddress = {
                walletAddress?.let { addr ->
                    copyToClipboard(context, "Ethereum Address", addr)
                    scope.launch { snackbarHostState.showSnackbar("Η διεύθυνση πορτοφολιού αντιγράφηκε στο πρόχειρο") }
                }
            },
            onCopyInboxId = {
                inboxId?.let { id ->
                    copyToClipboard(context, "XMTP Inbox ID", id)
                    scope.launch { snackbarHostState.showSnackbar("Το XMTP Inbox ID αντιγράφηκε στο πρόχειρο") }
                }
            },
            onDismiss = { showIdentityDialog = false }
        )
    }

    if (showUpdateDialog || updateStatus is UpdateStatus.Downloading || updateStatus is UpdateStatus.ReadyToInstall || updateStatus is UpdateStatus.Error) {
        AppUpdateDialog(
            updateStatus = updateStatus,
            canRequestInstall = viewModel.canRequestPackageInstalls(),
            onDownload = { viewModel.downloadUpdate(it) },
            onInstall = { viewModel.installUpdate(it) },
            onOpenSettings = { viewModel.openInstallPermissionSettings() },
            onDismiss = {
                showUpdateDialog = false
                viewModel.dismissUpdate()
            }
        )
    }

    if (showQrDownloadDialog) {
        QrDownloadFullDialog(
            currentVersion = viewModel.getCurrentVersionName(),
            updateStatus = updateStatus,
            onCopyUrl = onCopyDownloadUrl,
            onOpenDownloadUrl = onOpenDownloadUrl,
            onDismiss = { showQrDownloadDialog = false }
        )
    }

    if (showShredConfirmationDialog) {
        GDPRShredConfirmationDialog(
            onDismiss = { showShredConfirmationDialog = false },
            onConfirmShred = {
                showShredConfirmationDialog = false
                viewModel.performCryptoShredding()
            }
        )
    }

    val inspectorConv = selectedConversation
    val inspectorReadyState = engineState as? XmtpEngineState.Ready
    if (showProtocolInspectorDialog && inspectorConv != null && inspectorReadyState != null) {
        MLSProtocolInspectorDialog(
            conversation = inspectorConv,
            localInboxId = inspectorReadyState.client.inboxId,
            messageCount = messages.size,
            onDismiss = { showProtocolInspectorDialog = false },
            onCopyTopic = {
                copyToClipboard(context, "MLS Topic Hash", inspectorConv.topic)
                scope.launch { snackbarHostState.showSnackbar("Το Topic του MLS group αντιγράφηκε στο πρόχειρο") }
            }
        )
    }

    if (showLogsScreen) {
        LogsTelemetryScreen(
            logs = actionLogs,
            walletAddress = walletAddress,
            inboxId = inboxId,
            installationId = installationId,
            verifiedMsgCount = verifiedMsgCount,
            isBenchmarking = isBenchmarking,
            snackbarHostState = snackbarHostState,
            onBack = { showLogsScreen = false },
            onClear = { viewModel.clearActionLogs() },
            onExport = onExportLogs,
            onShareReport = onShareReport,
            onRunBatchBenchmark = { viewModel.runBatchBenchmark(10) },
            onEmitBlockchainPoC = { viewModel.emitBlockchainPoCProof() }
        )
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                MessagingTopBar(
                    selectedConversation = selectedConversation,
                    walletAddress = walletAddress,
                    engineState = engineState,
                    isSyncing = isSyncing,
                    isShredding = isShredding,
                    onBack = { viewModel.selectConversation(null) },
                    onSync = { viewModel.syncConversations() },
                    onOpenIdentity = { showIdentityDialog = true },
                    onOpenShred = { showShredConfirmationDialog = true },
                    onOpenLogs = { showLogsScreen = true },
                    onOpenProtocolInspector = { showProtocolInspectorDialog = true }
                )
            }
        ) { padding ->
            // Consumimos los insets del Scaffold para que imePadding() en el chat no duplique el margen inferior.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
            ) {
                val availableUpdate = updateStatus as? UpdateStatus.UpdateAvailable
                if (selectedConversation == null && availableUpdate != null) {
                    UpdateAvailableBanner(
                        latestVersion = availableUpdate.updateInfo.latestVersion,
                        onOpenUpdate = { showUpdateDialog = true }
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (val state = engineState) {
                        is XmtpEngineState.Idle -> {
                            MessagingStartScreen(
                                onStart = { viewModel.initializeSession() },
                                onOpenQrDownload = { showQrDownloadDialog = true },
                                lastShredProof = lastShredProof,
                                onCopyShredProof = { proof ->
                                    copyToClipboard(
                                        context = context,
                                        label = "GDPR Audit Certificate",
                                        text = proof.toCertificateSummary(),
                                        sensitive = false
                                    )
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Το πιστοποιητικό GDPR SHA-256 αντιγράφηκε στο πρόχειρο")
                                    }
                                },
                                onOpenLogs = { showLogsScreen = true }
                            )
                        }
                        is XmtpEngineState.Loading -> {
                            MessagingLoadingScreen()
                        }
                        is XmtpEngineState.Error -> {
                            MessagingConnectionError(
                                state = state,
                                onRetry = { viewModel.initializeSession() }
                            )
                        }
                        is XmtpEngineState.Ready -> {
                            if (selectedConversation != null) {
                                MessagingChatScreen(
                                    messages = messages,
                                    localInboxId = state.client.inboxId,
                                    draft = draft,
                                    isSending = isSending,
                                    isSyncing = isSyncing,
                                    isShredding = isShredding,
                                    onSync = { viewModel.syncConversations() },
                                    onDraftChange = { draft = it },
                                    onSendMessage = { text ->
                                        draft = ""
                                        viewModel.sendMessage(
                                            text = text,
                                            onFailureRestore = {
                                                if (draft.isBlank()) draft = text
                                            }
                                        )
                                    },
                                    onRetryMessage = { body ->
                                        viewModel.sendMessage(body)
                                    }
                                )
                            } else {
                                InboxDashboardScreen(
                                    conversations = conversations,
                                    engineState = engineState,
                                    isCreatingConversation = isCreatingConversation,
                                    isShredding = isShredding,
                                    actionLogs = actionLogs,
                                    currentVersion = viewModel.getCurrentVersionName(),
                                    updateStatus = updateStatus,
                                    onOpenLogs = { showLogsScreen = true },
                                    onNewConversation = { showNewConversationDialog = true },
                                    onStartLoopback = { viewModel.startSelfConversation() },
                                    onSelectConversation = { conv -> viewModel.selectConversation(conv) },
                                    onCopyDownloadUrl = onCopyDownloadUrl,
                                    onOpenDownloadUrl = onOpenDownloadUrl,
                                    onExpandQr = { showQrDownloadDialog = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
