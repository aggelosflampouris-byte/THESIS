package com.example.thesis

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.thesis.ui.ChatViewModel
import com.example.thesis.ui.components.MessagingTopBar
import com.example.thesis.ui.components.QrDownloadFullDialog
import com.example.thesis.ui.components.ScreenRecordingShieldOverlay
import com.example.thesis.ui.components.UpdateAvailableBanner
import com.example.thesis.ui.components.messagingStatusText
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.security.ScreenSecurityManager
import com.example.thesis.security.ScreenSecurityPolicy
import com.example.thesis.ui.dialogs.AppUpdateDialog
import com.example.thesis.ui.dialogs.EditContactNicknameDialog
import com.example.thesis.ui.dialogs.EditMyNicknameDialog
import com.example.thesis.ui.dialogs.GDPRShredConfirmationDialog
import org.xmtp.android.library.Conversation
import com.example.thesis.ui.dialogs.MLSProtocolInspectorDialog
import com.example.thesis.ui.dialogs.MediaAttachmentViewerDialog
import com.example.thesis.ui.dialogs.MyIdentityQrDialog
import com.example.thesis.ui.dialogs.NewConversationDialog
import com.example.thesis.media.MediaAttachmentProcessor
import com.example.thesis.xmtp.MediaAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.thesis.ui.screens.InboxDashboardScreen
import com.example.thesis.ui.screens.LogsTelemetryScreen
import com.example.thesis.ui.screens.MessagingChatScreen
import com.example.thesis.ui.screens.MessagingConnectionError
import com.example.thesis.ui.screens.MessagingLoadingScreen
import com.example.thesis.ui.screens.MessagingStartScreen
import com.example.thesis.ui.screens.SettingsScreen
import com.example.thesis.ui.theme.ThesisTheme
import com.example.thesis.call.CallNotificationManager
import com.example.thesis.call.CallUiState
import com.example.thesis.notifications.MessageNotificationManager
import com.example.thesis.ui.screens.ActiveCallOverlay
import com.example.thesis.ui.screens.MinimizedCallBanner
import com.example.thesis.ui.util.ReportExporter
import com.example.thesis.ui.util.copyToClipboard
import com.example.thesis.updater.UpdateConfig
import com.example.thesis.updater.UpdateStatus
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.launch
import java.util.function.Consumer
import com.example.thesis.xmtp.cachedGroupName
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.thesis.security.DeviceAuthHelper
import com.example.thesis.ui.DeviceTransferState
import com.example.thesis.ui.dialogs.DeviceTransferDialog

class MainActivity : FragmentActivity() {

    private val viewModel: ChatViewModel by viewModels()
    // Almacenamos como Any? para evitar verificacion de clases de API 34+ al cargar la actividad en Android antiguo.
    private var screenCaptureHandle: Any? = null
    private var screenRecordingHandle: Consumer<Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // Iniciamos una nueva sesion de registros cada vez que la aplicacion se abre tras haberse cerrado.
            (application as? ThesisApplication)?.sessionContainer?.actionLogStore?.onAppOpened()
        }
        ScreenSecurityManager.applyWindowProtection(this)
        enableEdgeToEdge()
        handleNotificationIntent(intent)
        setContent {
            ThesisTheme {
                ThesisMessagingApp(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ScreenSecurityManager.applyWindowProtection(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureHandle = ScreenSecurityManager.registerScreenCaptureObserver(this) {
                viewModel.onScreenCaptureAttemptDetected()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            screenRecordingHandle = ScreenSecurityManager.registerScreenRecordingObserver(this) { isActive ->
                viewModel.onScreenRecordingStateChanged(isActive)
            }
        }
        viewModel.onAppForegroundChanged(true)
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ScreenSecurityManager.unregisterScreenCaptureObserver(this, screenCaptureHandle)
            screenCaptureHandle = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ScreenSecurityManager.unregisterScreenRecordingObserver(this, screenRecordingHandle)
            screenRecordingHandle = null
        }
        viewModel.onScreenRecordingStateChanged(false)
        viewModel.onAppForegroundChanged(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val conversationId = intent?.getStringExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID)
            ?: intent?.getStringExtra(CallNotificationManager.EXTRA_CONVERSATION_ID)
        if (!conversationId.isNullOrBlank()) {
            viewModel.openConversationById(conversationId)
            intent?.removeExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID)
            intent?.removeExtra(CallNotificationManager.EXTRA_CONVERSATION_ID)
        }

        val callAction = intent?.getStringExtra(CallNotificationManager.EXTRA_CALL_ACTION)
        if (callAction == CallNotificationManager.ACTION_ACCEPT_CALL) {
            val callId = intent.getStringExtra(CallNotificationManager.EXTRA_CALL_ID)
            viewModel.requestNotificationCallAccept(callId)
            intent.removeExtra(CallNotificationManager.EXTRA_CALL_ACTION)
            intent.removeExtra(CallNotificationManager.EXTRA_CALL_ID)
        } else if (!callAction.isNullOrBlank()) {
            intent?.removeExtra(CallNotificationManager.EXTRA_CALL_ACTION)
            intent?.removeExtra(CallNotificationManager.EXTRA_CALL_ID)
        }
    }
}

private enum class HomeTab {
    CHATS,
    SETTINGS
}

@Composable
fun ThesisMessagingApp(viewModel: ChatViewModel) {
    val engineState by viewModel.engineState.collectAsState()
    val walletAddress by viewModel.walletAddress.collectAsState()
    val inboxId by viewModel.inboxId.collectAsState()
    val selectedConversation by viewModel.selectedConversation.collectAsState()
    val statusBanner by viewModel.statusBanner.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isShredding by viewModel.isShredding.collectAsState()
    val lastShredProof by viewModel.lastShredProof.collectAsState()
    val nicknameSnapshot by viewModel.nicknameSnapshot.collectAsState()
    val isScreenRecordingActive by viewModel.isScreenRecordingActive.collectAsState()
    val deviceTransferState by viewModel.deviceTransferState.collectAsState()
    val callState by viewModel.callState.collectAsState()
    val pendingCallAcceptId by viewModel.pendingCallNotificationAcceptId.collectAsState()
    var isCallOverlayMinimized by rememberSaveable(callState.callId) { mutableStateOf(false) }

    val updateStatus by viewModel.updateStatus.collectAsState()
    var selectedHomeTab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showNewConversationDialog by rememberSaveable { mutableStateOf(false) }
    var showMyIdentityQrDialog by rememberSaveable { mutableStateOf(false) }
    var showEditMyNicknameDialog by rememberSaveable { mutableStateOf(false) }
    var showShredConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showLogsScreen by rememberSaveable { mutableStateOf(false) }
    var showProtocolInspectorDialog by rememberSaveable { mutableStateOf(false) }
    var showQrDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var showExportNoLockConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var nicknameEditTarget by remember { mutableStateOf<Conversation?>(null) }

    var draft by rememberSaveable(selectedConversation?.id) { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(viewModel.hasCameraPermission()) }
    val qrCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted || viewModel.hasCameraPermission()
        if (!hasCameraPermission) {
            scope.launch {
                snackbarHostState.showSnackbar("Απαιτείται άδεια κάμερας για σάρωση QR.")
            }
        }
    }

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

    val onSharePdfReport: () -> Unit = {
        val report = viewModel.exportBenchmarkReport()
        ReportExporter.sharePdfReport(
            context = context,
            reportTitle = "THESIS XMTP Crypto & Benchmark Report (v${viewModel.getCurrentVersionName()})",
            reportMarkdown = report
        )
            .onSuccess {
                scope.launch {
                    snackbarHostState.showSnackbar("Έτοιμη εκτύπωση / κοινοποίηση PDF (.pdf)")
                }
            }
            .onFailure {
                scope.launch {
                    snackbarHostState.showSnackbar("Αποτυχία δημιουργίας αρχείου PDF")
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

    val requestDeviceTransferSend: () -> Unit = {
        val hostActivity = context as? FragmentActivity
        if (hostActivity == null) {
            scope.launch {
                snackbarHostState.showSnackbar("Αδύνατη η επαλήθευση συσκευής.")
            }
        } else {
            DeviceAuthHelper.authenticateForSensitiveExport(
                activity = hostActivity,
                onAuthenticated = {
                    viewModel.openSenderTransfer()
                },
                onRequiresManualFallback = {
                    showExportNoLockConfirmDialog = true
                },
                onError = { err ->
                    scope.launch { snackbarHostState.showSnackbar("Σφάλμα επαλήθευσης: $err") }
                }
            )
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAudioAction
        pendingAudioAction = null
        if (granted) {
            action?.invoke()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Απαιτείται άδεια μικροφώνου για κλήσεις φωνής.")
            }
        }
    }

    val runWithRecordAudioPermission: (() -> Unit) -> Unit = { action ->
        if (viewModel.hasRecordAudioPermission()) {
            action()
        } else {
            pendingAudioAction = action
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var pendingVideoAction by remember { mutableStateOf<Pair<Boolean, () -> Unit>?>(null) }
    val videoCallPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val pending = pendingVideoAction
        pendingVideoAction = null
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true || viewModel.hasRecordAudioPermission()
        val cameraGranted = results[Manifest.permission.CAMERA] == true || viewModel.hasCameraPermission()
        if (pending != null) {
            val (requireCamera, action) = pending
            if (audioGranted && (!requireCamera || cameraGranted)) {
                action()
            } else {
                val msg = if (!audioGranted) {
                    "Απαιτείται άδεια μικροφώνου για κλήσεις."
                } else {
                    "Απαιτείται άδεια κάμερας για εκκίνηση βιντεοκλήσης."
                }
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }
        }
    }

    val runWithVideoCallPermissions: (Boolean, () -> Unit) -> Unit = { requireCamera, action ->
        val hasAudio = viewModel.hasRecordAudioPermission()
        val hasCamera = viewModel.hasCameraPermission()
        if (hasAudio && hasCamera) {
            action()
        } else {
            pendingVideoAction = Pair(requireCamera, action)
            videoCallPermissionsLauncher.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            )
        }
    }

    LaunchedEffect(pendingCallAcceptId) {
        val acceptCallId = pendingCallAcceptId ?: return@LaunchedEffect
        if (callState.isVideo) {
            runWithVideoCallPermissions(false) {
                viewModel.acceptIncomingCall(acceptCallId.takeIf { it.isNotBlank() })
            }
        } else {
            runWithRecordAudioPermission {
                viewModel.acceptIncomingCall(acceptCallId.takeIf { it.isNotBlank() })
            }
        }
        viewModel.consumeNotificationCallAccept()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !viewModel.hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(showLogsScreen) {
        viewModel.onLogsScreenVisibilityChanged(showLogsScreen)
    }

    LaunchedEffect(selectedConversation) {
        if (selectedConversation != null) {
            if (showLogsScreen) showLogsScreen = false
            if (isSearchOpen) {
                isSearchOpen = false
                searchQuery = ""
            }
        }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { message ->
            snackbarHostState.showSnackbar(messagingStatusText(message))
            viewModel.consumeStatusBanner()
        }
    }

    BackHandler(enabled = callState !is CallUiState.Idle && !isCallOverlayMinimized) {
        // Permitimos minimizar la pantalla de llamada con Atras para poder cambiar a registros o chat en cualquier momento.
        if (callState is CallUiState.Ended) {
            viewModel.dismissEndedCall()
        } else {
            isCallOverlayMinimized = true
        }
    }

    BackHandler(enabled = showLogsScreen) {
        showLogsScreen = false
    }

    BackHandler(enabled = !showLogsScreen && selectedConversation != null) {
        viewModel.selectConversation(null)
    }

    BackHandler(enabled = !showLogsScreen && selectedConversation == null && isSearchOpen) {
        searchQuery = ""
        isSearchOpen = false
    }

    BackHandler(enabled = !showLogsScreen && selectedConversation == null && !isSearchOpen && selectedHomeTab != HomeTab.CHATS) {
        selectedHomeTab = HomeTab.CHATS
    }

    BackHandler(
        enabled = !showLogsScreen &&
            selectedConversation == null &&
            !isSearchOpen &&
            selectedHomeTab == HomeTab.CHATS &&
            callState is CallUiState.Idle
    ) {
        // Finalizamos la actividad al salir con Atras para que la siguiente apertura cree una nueva sesion de registros.
        (context as? FragmentActivity)?.finish()
    }

    if (showNewConversationDialog) {
        NewConversationDialog(
            canUseCamera = hasCameraPermission,
            onRequestCameraPermission = { qrCameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            onDismiss = { showNewConversationDialog = false },
            onStartConversation = { target, contactNick ->
                showNewConversationDialog = false
                viewModel.startNewConversation(target, contactNick)
            }
        )
    }

    nicknameEditTarget?.let { targetConv ->
        val contactKey = if (targetConv is Conversation.Dm) targetConv.dm.peerInboxId else targetConv.id
        val normalizedKey = NicknamePolicy.normalizeKey(contactKey)
        val targetLabel = if (targetConv is Conversation.Dm) {
            val peer = targetConv.dm.peerInboxId
            if (peer.length >= 16) "${peer.take(8)}...${peer.takeLast(6)}" else peer
        } else {
            "Group ${targetConv.id.take(8)}..."
        }
        EditContactNicknameDialog(
            targetLabel = targetLabel,
            currentLocalNickname = nicknameSnapshot.localNicknames[normalizedKey],
            sharedPeerNickname = if (targetConv is Conversation.Dm) nicknameSnapshot.sharedNicknames[normalizedKey] else null,
            onDismiss = { nicknameEditTarget = null },
            onSave = { nick ->
                nicknameEditTarget = null
                viewModel.updateContactNickname(contactKey, nick)
            }
        )
    }

    if (showMyIdentityQrDialog) {
        MyIdentityQrDialog(
            address = walletAddress,
            inboxId = inboxId,
            onCopyAddress = {
                walletAddress?.let { addr ->
                    copyToClipboard(context, "Ethereum Address", addr)
                    scope.launch { snackbarHostState.showSnackbar("Η διεύθυνση πορτοφολιού αντιγράφηκε") }
                }
            },
            onCopyInboxId = {
                inboxId?.let { id ->
                    copyToClipboard(context, "XMTP Inbox ID", id)
                    scope.launch { snackbarHostState.showSnackbar("Το Inbox ID αντιγράφηκε") }
                }
            },
            onDismiss = { showMyIdentityQrDialog = false }
        )
    }

    if (showEditMyNicknameDialog) {
        EditMyNicknameDialog(
            currentNickname = nicknameSnapshot.myNickname,
            onSave = { nick ->
                showEditMyNicknameDialog = false
                viewModel.updateMyNickname(nick)
            },
            onDismiss = { showEditMyNicknameDialog = false }
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

    if (deviceTransferState !is DeviceTransferState.Idle) {
        DeviceTransferDialog(
            state = deviceTransferState,
            onPairingUriSubmitted = { uri -> viewModel.startSendingToPairingUri(uri) },
            onToggleRevokeOldDevice = { enabled -> viewModel.setRevokeOldDeviceAfterImport(enabled) },
            onCancel = { viewModel.cancelDeviceTransfer() },
            onWipeOldDeviceAfterTransfer = {
                viewModel.cancelDeviceTransfer()
                viewModel.performCryptoShredding()
            }
        )
    }

    if (showExportNoLockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExportNoLockConfirmDialog = false },
            properties = ScreenSecurityManager.dialogProperties(dismissOnClickOutside = false),
            title = { Text("Επιβεβαίωση Εξαγωγής Ταυτότητας") },
            text = {
                Text("Η συσκευή δεν διαθέτει ενεργό κλείδωμα οθόνης (PIN/βιομετρικά). Θέλετε να προχωρήσετε στη μεταφορά του ιδιωτικού κλειδιού;")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportNoLockConfirmDialog = false
                        viewModel.openSenderTransfer()
                    }
                ) {
                    Text("Συνέχεια Εξαγωγής")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportNoLockConfirmDialog = false }) {
                    Text("Ακύρωση")
                }
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
                nicknameEditTarget = null
                viewModel.performCryptoShredding()
            }
        )
    }

    val inspectorConv = selectedConversation
    val inspectorReadyState = engineState as? XmtpEngineState.Ready
    if (showProtocolInspectorDialog && inspectorConv != null && inspectorReadyState != null) {
        MLSProtocolInspectorDialogHost(
            viewModel = viewModel,
            conversation = inspectorConv,
            localInboxId = inspectorReadyState.client.inboxId,
            nicknameSnapshot = nicknameSnapshot,
            onEditContactNickname = {
                showProtocolInspectorDialog = false
                nicknameEditTarget = inspectorConv
            },
            onDismiss = { showProtocolInspectorDialog = false },
            onCopyTopic = {
                copyToClipboard(context, "MLS Topic Hash", inspectorConv.topic)
                scope.launch { snackbarHostState.showSnackbar("Το Topic του MLS group αντιγράφηκε στο πρόχειρο") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showLogsScreen) {
            LogsTelemetryScreenHost(
                viewModel = viewModel,
                walletAddress = walletAddress,
                inboxId = inboxId,
                snackbarHostState = snackbarHostState,
                onBack = { showLogsScreen = false },
                onExport = onExportLogs,
                onShareReport = onShareReport,
                onSharePdfReport = onSharePdfReport
            )
        } else {
            val isHomeReady = engineState is XmtpEngineState.Ready && selectedConversation == null
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    MessagingTopBar(
                        selectedConversation = selectedConversation,
                        nicknameSnapshot = nicknameSnapshot,
                        engineState = engineState,
                        isSettingsTab = isHomeReady && selectedHomeTab == HomeTab.SETTINGS,
                        isSearchOpen = isHomeReady && selectedHomeTab == HomeTab.CHATS && isSearchOpen,
                        searchQuery = searchQuery,
                        isSyncing = isSyncing,
                        isShredding = isShredding,
                        isCallActive = callState !is CallUiState.Idle,
                        onSearchOpenChange = { isSearchOpen = it },
                        onSearchQueryChange = { searchQuery = it },
                        onBack = { viewModel.selectConversation(null) },
                        onSync = { viewModel.syncConversations() },
                        onOpenLogs = { showLogsScreen = true },
                        onOpenProtocolInspector = { showProtocolInspectorDialog = true },
                        onEditContactNickname = {
                            selectedConversation?.let { nicknameEditTarget = it }
                        },
                        onStartVoiceCall = {
                            val targetConv = selectedConversation
                            if (targetConv != null) {
                                runWithRecordAudioPermission {
                                    viewModel.startVoiceCall(targetConv)
                                }
                            }
                        },
                        onStartVideoCall = {
                            val targetConv = selectedConversation
                            if (targetConv != null) {
                                runWithVideoCallPermissions(true) {
                                    viewModel.startVideoCall(targetConv)
                                }
                            }
                        }
                    )
                },
                bottomBar = {
                    if (isHomeReady) {
                        Column {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp
                            ) {
                                NavigationBarItem(
                                    selected = selectedHomeTab == HomeTab.CHATS,
                                    onClick = { selectedHomeTab = HomeTab.CHATS },
                                    icon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_chat_bubble),
                                            contentDescription = null,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = { Text("Συνομιλίες") }
                                )
                                NavigationBarItem(
                                    selected = selectedHomeTab == HomeTab.SETTINGS,
                                    onClick = {
                                        isSearchOpen = false
                                        searchQuery = ""
                                        selectedHomeTab = HomeTab.SETTINGS
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = null
                                        )
                                    },
                                    label = { Text("Ρυθμίσεις") }
                                )
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (isHomeReady && selectedHomeTab == HomeTab.CHATS) {
                        FloatingActionButton(
                            onClick = { showNewConversationDialog = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Νέα συνομιλία"
                            )
                        }
                    }
                }
            ) { padding ->
                // Consumimos los insets del Scaffold para que imePadding() en el chat no duplique el margen inferior.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                ) {
                    if (callState !is CallUiState.Idle && isCallOverlayMinimized) {
                        MinimizedCallBanner(
                            callState = callState,
                            onExpand = { isCallOverlayMinimized = false },
                            onAccept = {
                                isCallOverlayMinimized = false
                                if (callState.isVideo) {
                                    runWithVideoCallPermissions(false) {
                                        viewModel.acceptIncomingCall()
                                    }
                                } else {
                                    runWithRecordAudioPermission {
                                        viewModel.acceptIncomingCall()
                                    }
                                }
                            },
                            onDeclineOrHangup = {
                                if (callState is CallUiState.IncomingRinging) {
                                    viewModel.declineIncomingCall()
                                } else {
                                    viewModel.hangupCall()
                                }
                            }
                        )
                    }
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
                                onReceiveFromOldDevice = { viewModel.startReceiverTransfer() },
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
                            val activeConv = selectedConversation
                            if (activeConv != null) {
                                MessagingChatScreenHost(
                                    viewModel = viewModel,
                                    activeConv = activeConv,
                                    localInboxId = state.client.inboxId,
                                    nicknameSnapshot = nicknameSnapshot,
                                    isSyncing = isSyncing,
                                    isShredding = isShredding,
                                    isCallIdle = callState is CallUiState.Idle,
                                    draft = draft,
                                    snackbarHostState = snackbarHostState,
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
                                    onStartVoiceCall = { dm ->
                                        runWithRecordAudioPermission {
                                            viewModel.startVoiceCall(dm)
                                        }
                                    },
                                    onStartVoiceRecording = {
                                        runWithRecordAudioPermission {
                                            viewModel.startVoiceRecording()
                                        }
                                    },
                                    onCopyMessageText = { text ->
                                        copyToClipboard(context, "Message", text, sensitive = false)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Το μήνυμα αντιγράφηκε")
                                        }
                                    }
                                )
                            } else if (selectedHomeTab == HomeTab.SETTINGS) {
                                SettingsScreenHost(
                                    viewModel = viewModel,
                                    walletAddress = walletAddress,
                                    inboxId = inboxId,
                                    myNickname = nicknameSnapshot.myNickname,
                                    updateStatus = updateStatus,
                                    isShredding = isShredding,
                                    onEditMyNickname = { showEditMyNicknameDialog = true },
                                    onOpenIdentityQr = { showMyIdentityQrDialog = true },
                                    onTransferToNewDevice = requestDeviceTransferSend,
                                    onReceiveFromOldDevice = { viewModel.startReceiverTransfer() },
                                    onOpenQrDownload = { showQrDownloadDialog = true },
                                    onOpenLogs = { showLogsScreen = true },
                                    onOpenShred = { showShredConfirmationDialog = true }
                                )
                            } else {
                                InboxDashboardScreenHost(
                                    viewModel = viewModel,
                                    nicknameSnapshot = nicknameSnapshot,
                                    searchQuery = searchQuery,
                                    onEditContactNickname = { conv -> nicknameEditTarget = conv }
                                )
                            }
                        }
                    }
                }
            }
        }
        }
        if (callState !is CallUiState.Idle && !isCallOverlayMinimized) {
            val callEglContext = remember(callState.isVideo) {
                if (callState.isVideo) viewModel.getCallEglBaseContext() else null
            }
            ActiveCallOverlay(
                callState = callState,
                eglBaseContext = callEglContext,
                suppressVideoSurfaces = ScreenSecurityPolicy.shouldShowPrivacyShield(isScreenRecordingActive),
                onBindLocalVideoSink = { sink -> viewModel.setLocalVideoSink(sink) },
                onBindRemoteVideoSink = { sink -> viewModel.setRemoteVideoSink(sink) },
                onAccept = {
                    if (callState.isVideo) {
                        runWithVideoCallPermissions(false) {
                            viewModel.acceptIncomingCall()
                        }
                    } else {
                        runWithRecordAudioPermission {
                            viewModel.acceptIncomingCall()
                        }
                    }
                },
                onDecline = { viewModel.declineIncomingCall() },
                onHangup = { viewModel.hangupCall() },
                onToggleMute = { viewModel.toggleCallMute() },
                onToggleSpeaker = { viewModel.toggleCallSpeaker() },
                onToggleCamera = { viewModel.toggleCallCamera() },
                onSwitchCamera = { viewModel.switchCallCamera() },
                onDismissEnded = { viewModel.dismissEndedCall() },
                onMinimize = { isCallOverlayMinimized = true }
            )
        }
        if (ScreenSecurityPolicy.shouldShowPrivacyShield(isScreenRecordingActive)) {
            ScreenRecordingShieldOverlay()
        }
    }
}

@Composable
private fun LogsTelemetryScreenHost(
    viewModel: ChatViewModel,
    walletAddress: String?,
    inboxId: String?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onShareReport: () -> Unit,
    onSharePdfReport: () -> Unit
) {
    // Recolectamos registros y metricas locales solo al mostrar la pantalla de diagnostico.
    val actionLogs by viewModel.actionLogs.collectAsState()
    val actionLogSessions by viewModel.actionLogSessions.collectAsState()
    val installationId by viewModel.installationId.collectAsState()
    val verifiedMsgCount by viewModel.verifiedMessageCount.collectAsState()
    val isBenchmarking by viewModel.isBenchmarking.collectAsState()
    val cryptoAuditReport by viewModel.cryptoAuditReport.collectAsState()
    val isRunningCryptoAudit by viewModel.isRunningCryptoAudit.collectAsState()

    LogsTelemetryScreen(
        logs = actionLogs,
        sessions = actionLogSessions,
        walletAddress = walletAddress,
        inboxId = inboxId,
        installationId = installationId,
        verifiedMsgCount = verifiedMsgCount,
        isBenchmarking = isBenchmarking,
        cryptoAuditReport = cryptoAuditReport,
        isRunningCryptoAudit = isRunningCryptoAudit,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onClear = { viewModel.clearActionLogs() },
        onExport = onExport,
        onShareReport = onShareReport,
        onSharePdfReport = onSharePdfReport,
        onRunCryptoAudit = { viewModel.runCryptoAudit() },
        onRunBatchBenchmark = { viewModel.runBatchBenchmark(10) },
        onEmitBlockchainPoC = { viewModel.emitBlockchainPoCProof() }
    )
}

@Composable
private fun MessagingChatScreenHost(
    viewModel: ChatViewModel,
    activeConv: Conversation,
    localInboxId: String,
    nicknameSnapshot: NicknameSnapshot,
    isSyncing: Boolean,
    isShredding: Boolean,
    isCallIdle: Boolean,
    draft: String,
    snackbarHostState: SnackbarHostState,
    onDraftChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onStartVoiceCall: (Conversation.Dm) -> Unit,
    onStartVoiceRecording: () -> Unit,
    onCopyMessageText: (String) -> Unit
) {
    // Limitamos mensajes, estado de audio y adjuntos multimedia a esta vista para evitar recomposiciones en la raiz.
    val messages by viewModel.messages.collectAsState()
    val callEventsByConversation by viewModel.callEventsByConversation.collectAsState()
    val peerReadTimestamps by viewModel.peerReadTimestamps.collectAsState()
    val voiceRecordingState by viewModel.voiceRecordingState.collectAsState()
    val voicePlaybackState by viewModel.voicePlaybackState.collectAsState()
    val isUploadingMedia by viewModel.isUploadingMedia.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewingAttachment by remember { mutableStateOf<MediaAttachment?>(null) }
    var pendingSaveAttachment by remember { mutableStateOf<MediaAttachment?>(null) }
    // Conservamos la ruta temporal entre recreaciones de Activity mientras la app de camara esta abierta.
    var pendingCameraCapturePath by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val caption = draft.trim()
            if (caption.isNotEmpty()) onDraftChange("")
            viewModel.sendMediaFromUri(uri, caption)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val caption = draft.trim()
            if (caption.isNotEmpty()) onDraftChange("")
            viewModel.sendMediaFromUri(uri, caption)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraCapturePath?.let { File(it) }
        pendingCameraCapturePath = null
        if (success && file != null && file.exists() && file.length() > 0L) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val caption = draft.trim()
            if (caption.isNotEmpty()) onDraftChange("")
            viewModel.sendMediaFromUri(uri, caption, tempCaptureFile = file)
        } else {
            file?.delete()
        }
    }

    val launchCameraCapture: () -> Unit = {
        runCatching {
            val (captureFile, captureUri) = MediaAttachmentProcessor.createTempCameraCaptureFile(context)
            pendingCameraCapturePath?.let { File(it).delete() }
            pendingCameraCapturePath = captureFile.absolutePath
            takePictureLauncher.launch(captureUri)
        }.onFailure {
            pendingCameraCapturePath?.let { File(it).delete() }
            pendingCameraCapturePath = null
            scope.launch {
                snackbarHostState.showSnackbar("Αδύνατο το άνοιγμα κάμερας.")
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || viewModel.hasCameraPermission()) {
            launchCameraCapture()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Απαιτείται άδεια κάμερας για λήψη φωτογραφίας.")
            }
        }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { targetUri ->
        val attachment = pendingSaveAttachment
        pendingSaveAttachment = null
        if (targetUri != null && attachment != null) {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    MediaAttachmentProcessor.saveMediaAttachmentToUri(context, targetUri, attachment)
                }
                if (res.isSuccess) {
                    snackbarHostState.showSnackbar("Το αρχείο αποθηκεύτηκε στη συσκευή (${attachment.fileName})")
                } else {
                    snackbarHostState.showSnackbar("Αποτυχία αποθήκευσης αρχείου")
                }
            }
        }
    }

    val peerDisplayName = remember(activeConv, nicknameSnapshot) {
        if (activeConv is Conversation.Dm) {
            val peerId = activeConv.dm.peerInboxId
            NicknamePolicy.resolveContactNickname(peerId, nicknameSnapshot)
                ?: NicknamePolicy.resolveContactNickname(activeConv.id, nicknameSnapshot)
                ?: if (peerId.length > 14) "${peerId.take(6)}...${peerId.takeLast(4)}" else peerId
        } else {
            "Επαφή"
        }
    }

    MessagingChatScreen(
        messages = messages,
        callEvents = callEventsByConversation[activeConv.id].orEmpty(),
        peerDisplayName = peerDisplayName,
        localInboxId = localInboxId,
        peerLastReadTimestampMs = peerReadTimestamps[activeConv.id],
        draft = draft,
        isSyncing = isSyncing,
        isShredding = isShredding,
        isUploadingMedia = isUploadingMedia,
        voiceRecordingState = voiceRecordingState,
        voicePlaybackState = voicePlaybackState,
        onSync = { viewModel.syncConversations() },
        onStartVoiceCall = if (activeConv is Conversation.Dm && isCallIdle) {
            { onStartVoiceCall(activeConv) }
        } else null,
        onStartVoiceRecording = onStartVoiceRecording,
        onCancelVoiceRecording = { viewModel.cancelVoiceRecording() },
        onStopAndSendVoiceRecording = { viewModel.stopAndSendVoiceRecording() },
        onToggleVoicePlayback = { msgId, note -> viewModel.toggleVoicePlayback(msgId, note) },
        onSeekVoicePlayback = { msgId, fraction -> viewModel.seekVoicePlayback(msgId, fraction) },
        onPickPhoto = {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onTakePhoto = {
            if (viewModel.hasCameraPermission()) {
                launchCameraCapture()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onPickDocument = { documentPickerLauncher.launch(arrayOf("*/*")) },
        onOpenMediaAttachment = { attachment -> viewingAttachment = attachment },
        onCopyMessageText = onCopyMessageText,
        onDraftChange = onDraftChange,
        onSendMessage = onSendMessage,
        onRetryMessage = { body -> viewModel.sendMessage(body) }
    )

    viewingAttachment?.let { currentAttachment ->
        MediaAttachmentViewerDialog(
            attachment = currentAttachment,
            onSave = { item ->
                pendingSaveAttachment = item
                saveDocumentLauncher.launch(item.fileName)
            },
            onShare = { item ->
                scope.launch {
                    val shareRes = withContext(Dispatchers.IO) {
                        MediaAttachmentProcessor.shareMediaAttachment(context, item)
                    }
                    if (shareRes.isFailure) {
                        snackbarHostState.showSnackbar("Αποτυχία κοινοποίησης αρχείου")
                    }
                }
            },
            onDismiss = { viewingAttachment = null }
        )
    }
}

@Composable
private fun InboxDashboardScreenHost(
    viewModel: ChatViewModel,
    nicknameSnapshot: NicknameSnapshot,
    searchQuery: String,
    onEditContactNickname: (Conversation) -> Unit
) {
    // Recolectamos conversaciones, avances de mensajes y no leidos solo cuando la pestana Chats es visible.
    val conversations by viewModel.conversations.collectAsState()
    val previews by viewModel.conversationPreviews.collectAsState()
    val unreadCounts by viewModel.unreadCounts.collectAsState()

    InboxDashboardScreen(
        conversations = conversations,
        previews = previews,
        unreadCounts = unreadCounts,
        nicknameSnapshot = nicknameSnapshot,
        searchQuery = searchQuery,
        onSelectConversation = { conv -> viewModel.selectConversation(conv) },
        onEditContactNickname = onEditContactNickname
    )
}

@Composable
private fun SettingsScreenHost(
    viewModel: ChatViewModel,
    walletAddress: String?,
    inboxId: String?,
    myNickname: String?,
    updateStatus: UpdateStatus,
    isShredding: Boolean,
    onEditMyNickname: () -> Unit,
    onOpenIdentityQr: () -> Unit,
    onTransferToNewDevice: () -> Unit,
    onReceiveFromOldDevice: () -> Unit,
    onOpenQrDownload: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenShred: () -> Unit
) {
    val actionLogs by viewModel.actionLogs.collectAsState()
    val isCreatingConversation by viewModel.isCreatingConversation.collectAsState()

    SettingsScreen(
        myNickname = myNickname,
        walletAddress = walletAddress,
        inboxId = inboxId,
        currentVersion = viewModel.getCurrentVersionName(),
        updateStatus = updateStatus,
        notificationsEnabled = viewModel.hasNotificationPermission(),
        isServiceNotificationVisible = viewModel.isBackgroundServiceChannelVisible(),
        logsCount = actionLogs.size,
        isCreatingLoopback = isCreatingConversation,
        isShredding = isShredding,
        onEditMyNickname = onEditMyNickname,
        onOpenIdentityQr = onOpenIdentityQr,
        onOpenNotificationSettings = { viewModel.openNotificationSettings() },
        onOpenServiceNotificationSettings = { viewModel.openBackgroundServiceChannelSettings() },
        onTransferToNewDevice = onTransferToNewDevice,
        onReceiveFromOldDevice = onReceiveFromOldDevice,
        onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
        onOpenQrDownload = onOpenQrDownload,
        onStartLoopback = { viewModel.startSelfConversation() },
        onOpenLogs = onOpenLogs,
        onOpenShred = onOpenShred
    )
}

@Composable
private fun MLSProtocolInspectorDialogHost(
    viewModel: ChatViewModel,
    conversation: Conversation,
    localInboxId: String,
    nicknameSnapshot: NicknameSnapshot,
    onEditContactNickname: () -> Unit,
    onDismiss: () -> Unit,
    onCopyTopic: () -> Unit
) {
    // Observamos el flujo de mensajes solo mientras el inspector de protocolo esta abierto.
    val messages by viewModel.messages.collectAsState()
    val inspectorKey = if (conversation is Conversation.Dm) conversation.dm.peerInboxId else conversation.id
    val isLoopbackConv = conversation is Conversation.Group && conversation.group.cachedGroupName() == "MLS Research Loopback"
    MLSProtocolInspectorDialog(
        conversation = conversation,
        localInboxId = localInboxId,
        messageCount = messages.size,
        contactNickname = NicknamePolicy.resolveContactNickname(inspectorKey, nicknameSnapshot),
        onEditContactNickname = if (isLoopbackConv) null else onEditContactNickname,
        onDismiss = onDismiss,
        onCopyTopic = onCopyTopic
    )
}
