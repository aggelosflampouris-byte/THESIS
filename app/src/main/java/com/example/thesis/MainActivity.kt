package com.example.thesis

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.ui.ActionLogEntry
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.LinearProgressIndicator
import com.example.thesis.updater.AppUpdateInfo
import com.example.thesis.updater.UpdateStatus
import java.io.File
import com.example.thesis.ui.ChatViewModel
import com.example.thesis.util.CryptoUtils
import com.example.thesis.xmtp.XmtpEngineState
import com.example.thesis.xmtp.XmtpInitError
import kotlinx.coroutines.launch
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.libxmtp.DecodedMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ────────────────────────────────────────────────────────────────────────────
//  Modern Sage-Green Design System
// ────────────────────────────────────────────────────────────────────────────

private val MessagingPrimary = Color(0xFF315C54)
private val MessagingLightScheme = lightColorScheme(
    primary = MessagingPrimary,
    secondary = Color(0xFF52645F),
    tertiary = Color(0xFF6D5E40),
    background = Color(0xFFF8F9F7),
    surface = Color(0xFFF8F9F7),
    primaryContainer = Color(0xFFD4E8E1),
    onPrimaryContainer = Color(0xFF0F3831),
    secondaryContainer = Color(0xFFDBE5E0),
    onSecondaryContainer = Color(0xFF192A26),
    surfaceVariant = Color(0xFFE2E7E4)
)
private val MessagingDarkScheme = darkColorScheme(
    primary = Color(0xFFA3D0C2),
    secondary = Color(0xFFB8CBC3),
    tertiary = Color(0xFFE0C999),
    primaryContainer = Color(0xFF1E463E),
    onPrimaryContainer = Color(0xFFD4E8E1),
    secondaryContainer = Color(0xFF344641),
    onSecondaryContainer = Color(0xFFDBE5E0),
    surfaceVariant = Color(0xFF2B3A36)
)

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) MessagingDarkScheme else MessagingLightScheme
            ) {
                ThesisMessagingApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThesisMessagingApp(viewModel: ChatViewModel) {
    val engineState by viewModel.engineState.collectAsState()
    val walletAddress by viewModel.walletAddress.collectAsState()
    val inboxId by viewModel.inboxId.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val selectedConversation by viewModel.selectedConversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val statusBanner by viewModel.statusBanner.collectAsState()
    val actionLogs by viewModel.actionLogs.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isBenchmarking by viewModel.isBenchmarking.collectAsState()

    val updateStatus by viewModel.updateStatus.collectAsState()
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showNewConversationDialog by rememberSaveable { mutableStateOf(false) }
    var showIdentityDialog by rememberSaveable { mutableStateOf(false) }
    var showShredConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var isCreatingConversation by rememberSaveable { mutableStateOf(false) }
    var isActionLogExpanded by rememberSaveable { mutableStateOf(false) }
    var showTelemetryBottomSheet by rememberSaveable { mutableStateOf(false) }
    var showProtocolInspectorDialog by rememberSaveable { mutableStateOf(false) }

    var draft by rememberSaveable(selectedConversation?.id) { mutableStateOf("") }
    var failedDraft by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(updateStatus) {
        if (updateStatus is UpdateStatus.UpdateAvailable) {
            showUpdateDialog = true
        } else if (updateStatus is UpdateStatus.UpToDate) {
            snackbarHostState.showSnackbar("Η εφαρμογή είναι ενημερωμένη (v${(updateStatus as UpdateStatus.UpToDate).currentVersion})")
        }
    }
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val onExportLogs: () -> Unit = {
        val report = viewModel.exportBenchmarkReport()
        clipboardManager.setText(AnnotatedString(report))
        scope.launch {
            snackbarHostState.showSnackbar("Η αναφορά Benchmark αντιγράφηκε στο πρόχειρο (Markdown & CSV)")
        }
    }

    // Resilient Status Banner & Failure Recovery
    LaunchedEffect(statusBanner) {
        statusBanner?.let { message ->
            snackbarHostState.showSnackbar(messagingStatusText(message))
            if (message.contains("Αποτυχία", ignoreCase = true) ||
                message.contains("Σφάλμα", ignoreCase = true)
            ) {
                isCreatingConversation = false
                if (message.contains("αποστολής", ignoreCase = true)) {
                    failedDraft?.let { draft = it }
                    failedDraft = null
                }
            }
        }
    }

    LaunchedEffect(selectedConversation) {
        if (selectedConversation != null) {
            isCreatingConversation = false
        }
    }

    // Modal: New Conversation & Loopback Dispatch
    if (showNewConversationDialog) {
        NewConversationDialog(
            onDismiss = { showNewConversationDialog = false },
            onStartConversation = { target ->
                showNewConversationDialog = false
                isCreatingConversation = true
                viewModel.startNewConversation(target)
            },
            onStartLoopback = {
                showNewConversationDialog = false
                viewModel.startSelfConversation()
            }
        )
    }

    // Modal: Identity & Cryptographic Parameters
    if (showIdentityDialog) {
        IdentityDetailsDialog(
            address = walletAddress,
            inboxId = inboxId,
            currentVersion = viewModel.appUpdateManager.getCurrentVersionName(),
            isCheckingUpdate = updateStatus is UpdateStatus.Checking,
            onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
            onCopyAddress = {
                walletAddress?.let { addr ->
                    clipboardManager.setText(AnnotatedString(addr))
                    scope.launch { snackbarHostState.showSnackbar("Η διεύθυνση πορτοφολιού αντιγράφηκε στο πρόχειρο") }
                }
            },
            onCopyInboxId = {
                inboxId?.let { id ->
                    clipboardManager.setText(AnnotatedString(id))
                    scope.launch { snackbarHostState.showSnackbar("Το XMTP Inbox ID αντιγράφηκε στο πρόχειρο") }
                }
            },
            onTriggerShred = {
                showIdentityDialog = false
                showShredConfirmationDialog = true
            },
            onDismiss = { showIdentityDialog = false }
        )
    }

    if (showUpdateDialog || updateStatus is UpdateStatus.Downloading || updateStatus is UpdateStatus.ReadyToInstall || updateStatus is UpdateStatus.Error) {
        AppUpdateDialog(
            updateStatus = updateStatus,
            canRequestInstall = viewModel.appUpdateManager.canRequestPackageInstalls(),
            onDownload = { viewModel.downloadUpdate(it) },
            onInstall = { viewModel.installUpdate(it) },
            onOpenSettings = { viewModel.appUpdateManager.openInstallPermissionSettings() },
            onDismiss = {
                showUpdateDialog = false
                viewModel.dismissUpdate()
            }
        )
    }

    // Modal: GDPR Article 17 Cryptographic Shredding
    if (showShredConfirmationDialog) {
        GDPRShredConfirmationDialog(
            onDismiss = { showShredConfirmationDialog = false },
            onConfirmShred = {
                showShredConfirmationDialog = false
                viewModel.performCryptoShredding()
            }
        )
    }

    // Modal: MLS RFC 9420 Protocol & Ratchet Inspector
    if (showProtocolInspectorDialog && selectedConversation != null && engineState is XmtpEngineState.Ready) {
        MLSProtocolInspectorDialog(
            conversation = selectedConversation!!,
            localInboxId = (engineState as XmtpEngineState.Ready).client.inboxId,
            messageCount = messages.size,
            onDismiss = { showProtocolInspectorDialog = false },
            onCopyTopic = {
                clipboardManager.setText(AnnotatedString(selectedConversation!!.topic))
                scope.launch { snackbarHostState.showSnackbar("Το Topic του MLS group αντιγράφηκε στο πρόχειρο") }
            }
        )
    }

    // Bottom Sheet: Live Telemetry & In-Chat Benchmark Drawer
    if (showTelemetryBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTelemetryBottomSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔬 Telemetry & MLS Ratchet Inspector",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { showTelemetryBottomSheet = false }) {
                        Text("Κλείσιμο", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                ActionLogPanel(
                    logs = actionLogs,
                    isExpanded = true,
                    isBenchmarking = isBenchmarking,
                    onToggleExpand = {},
                    onClear = { viewModel.clearActionLogs() },
                    onExport = onExportLogs,
                    onRunBatchBenchmark = { viewModel.runBatchBenchmark(10) }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MessagingTopBar(
                selectedConversation = selectedConversation,
                walletAddress = walletAddress,
                engineState = engineState,
                isSyncing = isSyncing,
                updateStatus = updateStatus,
                onBack = { viewModel.selectConversation(null) },
                onSync = { viewModel.syncConversations() },
                onOpenUpdate = { showUpdateDialog = true },
                onOpenIdentity = { showIdentityDialog = true },
                onOpenShred = { showShredConfirmationDialog = true },
                onOpenTelemetrySheet = { showTelemetryBottomSheet = true },
                onOpenProtocolInspector = { showProtocolInspectorDialog = true }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = engineState) {
                is XmtpEngineState.Idle -> {
                    MessagingStartScreen(onStart = { viewModel.initializeSession() })
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
                            conversation = selectedConversation!!,
                            messages = messages,
                            localInboxId = state.client.inboxId,
                            draft = draft,
                            isSending = isSending,
                            isSyncing = isSyncing,
                            onSync = { viewModel.syncConversations() },
                            onDraftChange = { draft = it },
                            onSendMessage = { text ->
                                failedDraft = text
                                viewModel.sendMessage(text)
                                draft = ""
                            }
                        )
                    } else {
                        InboxDashboardScreen(
                            conversations = conversations,
                            engineState = engineState,
                            walletAddress = walletAddress,
                            isSyncing = isSyncing,
                            isCreatingConversation = isCreatingConversation,
                            actionLogs = actionLogs,
                            isActionLogExpanded = isActionLogExpanded,
                            onToggleActionLog = { isActionLogExpanded = !isActionLogExpanded },
                            onClearLogs = { viewModel.clearActionLogs() },
                            isBenchmarking = isBenchmarking,
                            onExportLogs = onExportLogs,
                            onRunBatchBenchmark = { viewModel.runBatchBenchmark(10) },
                            onNewConversation = { showNewConversationDialog = true },
                            onStartLoopback = { viewModel.startSelfConversation() },
                            onSelectConversation = { conv -> viewModel.selectConversation(conv) }
                        )
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  UI Component: Top App Bar
// ────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagingTopBar(
    selectedConversation: Conversation?,
    walletAddress: String?,
    engineState: XmtpEngineState,
    isSyncing: Boolean,
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenUpdate: () -> Unit = {},
    onOpenIdentity: () -> Unit,
    onOpenShred: () -> Unit,
    onOpenTelemetrySheet: () -> Unit = {},
    onOpenProtocolInspector: () -> Unit = {}
) {
    TopAppBar(
        title = {
            if (selectedConversation != null) {
                val headerTitle = when (selectedConversation) {
                    is Conversation.Dm -> {
                        val peer = (selectedConversation as Conversation.Dm).dm.peerInboxId
                        val masked = if (peer.length >= 14) "${peer.take(6)}...${peer.takeLast(4)}" else peer
                        "MLS DM: $masked"
                    }
                    is Conversation.Group -> {
                        val name = (selectedConversation as Conversation.Group).group.name
                        if (name.isNotBlank()) name else "MLS Chat: ${selectedConversation.topic.take(16)}..."
                    }
                }

                Column(
                    modifier = Modifier.clickable { onOpenProtocolInspector() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("ℹ️", fontSize = 12.sp)
                    }
                    Text(
                        text = "End-to-End Encrypted • SQLCipher Sandbox (Tap for RFC 9420)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "XMTP v4 (MLS)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "SQLCipher",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    walletAddress?.let { address ->
                        Text(
                            text = "ID: ${address.take(8)}...${address.takeLast(6)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (selectedConversation != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                }
            }
        },
        actions = {
            if (engineState is XmtpEngineState.Ready) {
                val rotation by animateFloatAsState(
                    targetValue = if (isSyncing) 360f else 0f,
                    label = "SyncRotation"
                )
                IconButton(onClick = onSync, enabled = !isSyncing) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Συγχρονισμός",
                        modifier = Modifier.rotate(rotation)
                    )
                }
            }

            if (updateStatus is UpdateStatus.UpdateAvailable) {
                Button(
                    onClick = onOpenUpdate,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("🚀 v${(updateStatus as UpdateStatus.UpdateAvailable).updateInfo.latestVersion}", fontSize = 11.sp)
                }
            }

            if (walletAddress != null) {
                IconButton(onClick = onOpenTelemetrySheet) {
                    Text("📊", fontSize = 16.sp)
                }

                TextButton(onClick = onOpenIdentity) {
                    Text("Ταυτότητα", fontSize = 12.sp)
                }

                Button(
                    onClick = onOpenShred,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("GDPR Shred", fontSize = 12.sp)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ────────────────────────────────────────────────────────────────────────────
//  UI Component: Start, Loading, Error Screens
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun MessagingStartScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Ασφαλής Επικοινωνία MLS",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ερευνητικό περιβάλλον ασφάλειας με Hardware Keystore KEK και κρυπτογραφημένο SQLCipher Sandbox.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        MessagingNotice(
            title = "Hardware-Backed Ασφάλεια",
            text = "Το Database Encryption Key (DEK) κρυπτογραφείται στο Android Keystore. Συμμόρφωση με GDPR Άρθρο 17 (Right to Erasure)."
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Εκκίνηση Hardware Keystore & XMTP MLS")
        }
    }
}

@Composable
private fun MessagingLoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(18.dp))
        Text("Hardware DEK Unwrap & Σύνδεση MLS...", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Συνδεόμαστε στο δίκτυο XMTP DEV και προετοιμάζουμε την κρυπτογραφημένη βάση SQLCipher.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessagingConnectionError(state: XmtpEngineState.Error, onRetry: () -> Unit) {
    val explanation = when (state.error) {
        is XmtpInitError.SignatureRejected ->
            "Η υπογραφή Secp256k1 ακυρώθηκε ή δεν έγινε αποδεκτή."
        is XmtpInitError.NetworkFailure ->
            "Δεν ήταν δυνατή η σύνδεση gRPC με τους κόμβους του δικτύου XMTP."
        is XmtpInitError.DatabaseError ->
            "Αποτυχία εκκίνησης του κρυπτογραφημένου SQLCipher database sandbox."
        is XmtpInitError.IdentityError ->
            "Αποτυχία εγγραφής του identity installation στο MLS ledger."
        is XmtpInitError.Unknown ->
            state.error.message ?: "Παρουσιάστηκε απρόσμενο πρόβλημα κατά τη σύνδεση."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text("Αποτυχία Σύνδεσης", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Επανάληψη Σύνδεσης")
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  UI Component: Inbox & Security Dashboard Screen
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun InboxDashboardScreen(
    conversations: List<Conversation>,
    engineState: XmtpEngineState,
    walletAddress: String?,
    isSyncing: Boolean,
    isCreatingConversation: Boolean,
    actionLogs: List<ActionLogEntry>,
    isActionLogExpanded: Boolean,
    onToggleActionLog: () -> Unit,
    onClearLogs: () -> Unit,
    isBenchmarking: Boolean = false,
    onExportLogs: () -> Unit = {},
    onRunBatchBenchmark: () -> Unit = {},
    onNewConversation: () -> Unit,
    onStartLoopback: () -> Unit,
    onSelectConversation: (Conversation) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // Live Security Status Card
        SecurityEngineStatusCard(
            engineState = engineState,
            walletAddress = walletAddress
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Compact Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onStartLoopback,
                modifier = Modifier.weight(1.2f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("▶ Loopback MLS (1-Device)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onNewConversation,
                enabled = !isCreatingConversation,
                modifier = Modifier.weight(0.8f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (isCreatingConversation) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Νέα", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ενεργές Συνομιλίες MLS (${conversations.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (conversations.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Δεν υπάρχουν ακόμη συνομιλίες",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Πατήστε 'Loopback MLS' για άμεση δημιουργία συνεδρίας και δοκιμή κρυπτογράφησης στη συσκευή σας.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(conversations, key = { _, conv -> conv.id }) { index, conv ->
                    ConversationItemCard(
                        index = index + 1,
                        conversation = conv,
                        onClick = { onSelectConversation(conv) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Collapsed Action & Telemetry Benchmark Drawer
        ActionLogPanel(
            logs = actionLogs,
            isExpanded = isActionLogExpanded,
            isBenchmarking = isBenchmarking,
            onToggleExpand = onToggleActionLog,
            onClear = onClearLogs,
            onExport = onExportLogs,
            onRunBatchBenchmark = onRunBatchBenchmark
        )
    }
}

@Composable
private fun ConversationItemCard(
    index: Int,
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDm = conversation is Conversation.Dm
    val isLoopback = conversation is Conversation.Group && (conversation as Conversation.Group).group.name == "MLS Research Loopback"

    val title = when {
        isDm -> {
            val peer = (conversation as Conversation.Dm).dm.peerInboxId
            val maskedPeer = if (peer.length >= 16) "${peer.take(8)}...${peer.takeLast(6)}" else peer
            "DM: $maskedPeer"
        }
        isLoopback -> "Loopback MLS (1-Device Test)"
        conversation is Conversation.Group -> {
            val name = (conversation as Conversation.Group).group.name
            if (name.isNotBlank()) name else "Ομάδα MLS: ${conversation.id.take(8)}..."
        }
        else -> "Συνομιλία $index: ${conversation.topic.take(18)}..."
    }

    val subtitle = when {
        isDm -> "👤 Direct Message • MLS Ratchet"
        isLoopback -> "🔄 Τοπική δοκιμή συσκευής • MLS Loopback"
        else -> "👥 Ομαδική συνομιλία • MLS Ratchet"
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isDm -> MaterialTheme.colorScheme.primaryContainer
                    isLoopback -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                },
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            isDm -> "👤"
                            isLoopback -> "🔄"
                            else -> "👥"
                        },
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ID: ${conversation.id.take(8)}...",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }

            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  UI Component: Pure Full-Height Chat Screen
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun MessagingChatScreen(
    conversation: Conversation,
    messages: List<DecodedMessage>,
    localInboxId: String,
    draft: String,
    isSending: Boolean,
    isSyncing: Boolean = false,
    onSync: () -> Unit = {},
    onDraftChange: (String) -> Unit,
    onSendMessage: (String) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // Message List takes maximum vertical height
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Δεν υπάρχουν ακόμη μηνύματα",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Στείλτε μήνυμα για να παρατηρήσετε την εξέλιξη του MLS ratchet tree.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onSync,
                        enabled = !isSyncing
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSyncing) "Συγχρονισμός..." else "Συγχρονισμός μηνυμάτων",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    messages,
                    key = { it.id ?: "${it.senderInboxId}-${it.sentAtNs}" }
                ) { message ->
                    MessagingMessageBubble(
                        message = message,
                        isOwnMessage = message.senderInboxId == localInboxId,
                        onRetry = { onSendMessage(message.body) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Quick Message Template Chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                AssistChip(
                    onClick = { onSendMessage("👋 Hello XMTP MLS!") },
                    label = { Text("👋 Hello XMTP!", fontSize = 10.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onSendMessage("🔐 SQLCipher Storage Test") },
                    label = { Text("🔐 Test SQLCipher", fontSize = 10.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onSendMessage("⚡ Benchmark MLS Latency") },
                    label = { Text("⚡ Benchmark", fontSize = 10.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Message Input Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Γράψτε μήνυμα...", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default
                ),
                enabled = !isSending,
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val content = draft.trim()
                    if (content.isNotEmpty()) onSendMessage(content)
                },
                enabled = draft.isNotBlank() && !isSending
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Αποστολή",
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagingMessageBubble(
    message: DecodedMessage,
    isOwnMessage: Boolean,
    onRetry: () -> Unit
) {
    val timeLabel = remember(message.sentAt) {
        message.sentAt?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(it) }
    }
    val status = when (message.deliveryStatus) {
        DecodedMessage.MessageDeliveryStatus.FAILED -> "Δεν στάλθηκε"
        DecodedMessage.MessageDeliveryStatus.UNPUBLISHED -> "Αποστολή…"
        DecodedMessage.MessageDeliveryStatus.PUBLISHED -> "Στάλθηκε"
        DecodedMessage.MessageDeliveryStatus.ALL -> null
    }
    val isFailed = message.deliveryStatus == DecodedMessage.MessageDeliveryStatus.FAILED

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomEnd = if (isOwnMessage) 4.dp else 16.dp,
                bottomStart = if (isOwnMessage) 16.dp else 4.dp
            ),
            color = if (isOwnMessage) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (timeLabel != null || (isOwnMessage && status != null)) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        timeLabel?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                        if (isOwnMessage && status != null) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isFailed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
        if (isOwnMessage && isFailed) {
            TextButton(onClick = onRetry) {
                Text("Προσπάθεια ξανά", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  UI Component: Dialogs
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun NewConversationDialog(
    onDismiss: () -> Unit,
    onStartConversation: (String) -> Unit,
    onStartLoopback: () -> Unit
) {
    var peerInput by rememberSaveable { mutableStateOf("") }
    val isValidInput = remember(peerInput) {
        val trimmed = peerInput.trim()
        CryptoUtils.isValidEthereumAddress(trimmed) || CryptoUtils.isValidInboxId(trimmed)
    }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Νέα Συνομιλία MLS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Επιλέξτε τρόπο σύνδεσης:")

                Button(
                    onClick = onStartLoopback,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("▶ Έναρξη Loopback (1-Device Test)")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("Ή εισάγετε ταυτότητα παραλήπτη:")

                OutlinedTextField(
                    value = peerInput,
                    onValueChange = { peerInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Διεύθυνση (0x...) ή XMTP Inbox ID") },
                    placeholder = { Text("0x… ή 64-hex inbox ID") },
                    singleLine = true,
                    isError = peerInput.isNotBlank() && !isValidInput,
                    supportingText = {
                        if (peerInput.isNotBlank() && !isValidInput) {
                            Text("Απαιτείται διεύθυνση Ethereum (0x...) ή XMTP Inbox ID (64 hex).")
                        } else {
                            Text("Υποστηρίζονται διευθύνσεις Ethereum (0x...) και XMTP Inbox IDs.", fontSize = 10.sp)
                        }
                    },
                    trailingIcon = {
                        TextButton(onClick = {
                            clipboard.getText()?.text?.let { peerInput = it.trim() }
                        }) {
                            Text("Επικόλληση", fontSize = 11.sp)
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStartConversation(peerInput.trim()) },
                enabled = isValidInput
            ) {
                Text("Σύνδεση")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Ακύρωση") }
        }
    )
}

@Composable
private fun IdentityDetailsDialog(
    address: String?,
    inboxId: String?,
    currentVersion: String = "1.0.5",
    isCheckingUpdate: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    onCopyAddress: () -> Unit,
    onCopyInboxId: () -> Unit,
    onTriggerShred: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ταυτότητα και Ασφάλεια") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Κατάσταση: Δοκιμαστική ταυτότητα SECP256K1")
                Text("Δίκτυο: XMTP DEV Network (MLS Protocol)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                address?.let {
                    Text("Διεύθυνση Πορτοφολιού (Ethereum)", style = MaterialTheme.typography.labelMedium)
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onCopyAddress, modifier = Modifier.fillMaxWidth()) {
                        Text("Αντιγραφή Διεύθυνσης")
                    }
                }

                inboxId?.let {
                    Text("XMTP Inbox ID (MLS Ledger)", style = MaterialTheme.typography.labelMedium)
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onCopyInboxId, modifier = Modifier.fillMaxWidth()) {
                        Text("Αντιγραφή Inbox ID")
                    }
                }

                Text(
                    "💡 Για επικοινωνία μεταξύ 2 συσκευών: Αντιγράψτε το Inbox ID ή τη Διεύθυνση και επικολλήστε τη στη 2η συσκευή στο '+ Νέα'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    "Η ταυτότητα προστατεύεται από Hardware-backed Master Key (KEK) στο Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Έκδοση: v$currentVersion", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCheckForUpdates, enabled = !isCheckingUpdate) {
                        Text(if (isCheckingUpdate) "Έλεγχος..." else "Έλεγχος Ενημερώσεων", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTriggerShred) {
                Text("GDPR Shred (Διαγραφή)", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Κλείσιμο") }
        }
    )
}

@Composable
private fun GDPRShredConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirmShred: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("GDPR Άρθρο 17: Οριστική Διαγραφή;") },
        text = {
            Text(
                "Η ενέργεια αυτή θα καταστρέψει οριστικά το Hardware Master Key (KEK) στο Android Keystore " +
                    "και θα εκτελέσει φυσικό μηδενισμό (zeroization) των κρυπτογραφημένων βάσεων SQLCipher. " +
                    "Τα τοπικά μηνύματα θα καταστούν μόνιμα μη ανακτήσιμα."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirmShred) {
                Text("Οριστική Διαγραφή", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Ακύρωση") }
        }
    )
}

@Composable
private fun MessagingNotice(title: String, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  UI Component: Security Engine Status Card (Thesis Component)
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SecurityEngineStatusCard(
    engineState: XmtpEngineState,
    walletAddress: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (engineState) {
                                    is XmtpEngineState.Ready -> Color(0xFF4CAF50)
                                    is XmtpEngineState.Loading -> Color(0xFFFF9800)
                                    is XmtpEngineState.Error -> Color(0xFFF44336)
                                    else -> Color.Gray
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (engineState) {
                            is XmtpEngineState.Ready -> "XMTP MLS Engine Έτοιμος"
                            is XmtpEngineState.Loading -> "Εκκίνηση Ασφαλούς Συνεδρίας..."
                            is XmtpEngineState.Error -> "Σφάλμα Σύνδεσης"
                            is XmtpEngineState.Idle -> "Ανενεργό"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "DEV Network",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusBadge(icon = "🛡️", text = "SQLCipher 4.6.1", modifier = Modifier.weight(1f))
                StatusBadge(icon = "🔑", text = "Keystore KEK", modifier = Modifier.weight(1f))
                StatusBadge(icon = "🔒", text = "MLS Ratchet", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusBadge(icon: String, text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = icon, fontSize = 11.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  UI Component: Action & Telemetry Log Panel (Thesis Benchmarking)
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun ActionLogPanel(
    logs: List<ActionLogEntry>,
    isExpanded: Boolean,
    isBenchmarking: Boolean = false,
    onToggleExpand: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit = {},
    onRunBatchBenchmark: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTagFilter by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(logs, selectedTagFilter) {
        if (selectedTagFilter == "ALL") logs
        else logs.filter { it.tag.equals(selectedTagFilter, ignoreCase = true) }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExpanded) "▼ Action & Telemetry Log (${logs.size})" else "▶ Action & Telemetry Log (${logs.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                if (isExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isBenchmarking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Testing...",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "Auto 10x",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .clickable(onClick = onRunBatchBenchmark)
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Export",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(onClick = onExport)
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            if (logs.isNotEmpty()) {
                                Text(
                                    text = "Clear",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clickable(onClick = onClear)
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))

                // Tag Filter Chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val filterTags = listOf("ALL", "BENCHMARK", "METRIC", "KEYSTORE", "MLS", "E2EE", "GDPR")
                    items(filterTags) { tag ->
                        FilterChip(
                            selected = selectedTagFilter == tag,
                            onClick = { selectedTagFilter = tag },
                            label = { Text(tag, fontSize = 9.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (filteredLogs.isEmpty()) {
                    Text(
                        text = "Δεν υπάρχουν καταγραφές για το φίλτρο $selectedTagFilter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                        fontSize = 11.sp
                    )
                } else {
                    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                    ) {
                        items(filteredLogs.reversed()) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = timeFormat.format(Date(entry.timestamp)),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "[${entry.tag}]",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (entry.isError) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = entry.message,
                                        fontSize = 10.sp,
                                        color = if (entry.isError) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                entry.durationMs?.let { ms ->
                                    Text(
                                        text = String.format(Locale.US, "%.1f ms", ms),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Status Text Normalization
// ────────────────────────────────────────────────────────────────────────────

private fun messagingStatusText(message: String): String {
    val normalized = message.lowercase(Locale.ROOT)
    return when {
        normalized.contains("crypto-shredded") || normalized.contains("shred") ->
            "Η οριστική διαγραφή ολοκληρώθηκε (GDPR Art. 17). Όλα τα κλειδιά και οι βάσεις καταστράφηκαν."
        normalized.contains("loopback") || normalized.contains("ενεργή") ->
            "Η πειραματική συνεδρία MLS (Loopback) είναι έτοιμη."
        normalized.contains("αποτυχ") || normalized.contains("σφάλμα") ->
            "Η ενέργεια δεν ολοκληρώθηκε. Ελέγξτε τη σύνδεση και προσπαθήστε ξανά."
        normalized.contains("αποστολ") ->
            "Το μήνυμα καταχωρήθηκε κρυπτογραφημένο στο MLS ratchet."
        else -> message
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  UI Component: MLS Protocol Inspector (RFC 9420 Research View)
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun MLSProtocolInspectorDialog(
    conversation: Conversation,
    localInboxId: String,
    messageCount: Int,
    onDismiss: () -> Unit,
    onCopyTopic: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔬", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "MLS Protocol Inspector",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InspectorParamRow(label = "Protocol Standard", value = "RFC 9420 (TreeKEM MLS)")
                InspectorParamRow(label = "Cipher Suite", value = "DHKEM-X25519-AES128GCM-SHA256")
                InspectorParamRow(
                    label = "Session Topology",
                    value = when (conversation.type) {
                        Conversation.Type.GROUP -> "MLS Group Ratchet (Loopback/Multi)"
                        Conversation.Type.DM -> "MLS 1-on-1 Direct Message"
                    }
                )
                InspectorParamRow(
                    label = "Local Client Inbox",
                    value = if (localInboxId.length >= 12) "${localInboxId.take(6)}...${localInboxId.takeLast(6)}" else localInboxId
                )
                InspectorParamRow(
                    label = "Commit Counter",
                    value = "$messageCount message commit(s)"
                )
                InspectorParamRow(
                    label = "Forward Secrecy",
                    value = "Continuous asymmetric ratchet update"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Column {
                    Text(
                        text = "MLS Topic Hash",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = conversation.topic,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onCopyTopic) {
                Text("Αντιγραφή Topic", fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Κλείσιμο", fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun InspectorParamRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}


@Composable
private fun AppUpdateDialog(
    updateStatus: UpdateStatus,
    canRequestInstall: Boolean,
    onDownload: (AppUpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    when (updateStatus) {
        is UpdateStatus.UpdateAvailable -> {
            val info = updateStatus.updateInfo
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = {
                    Text(
                        text = "Νέα Έκδοση: v${info.latestVersion}",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "Τρέχουσα: v${info.currentVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Text("➔", style = MaterialTheme.typography.bodySmall)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "Νέα: v${info.latestVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (info.releaseTitle.isNotBlank()) {
                            Text(
                                text = info.releaseTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (info.assetSizeBytes > 0L) {
                            val sizeMb = info.assetSizeBytes / (1024f * 1024f)
                            Text(
                                text = "Μέγεθος: ${String.format(Locale.US, "%.1f", sizeMb)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = "Σημειώσεις Έκδοσης:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = info.releaseNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { onDownload(info) }) {
                        Text("Λήψη & Ενημέρωση")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Αργότερα")
                    }
                }
            )
        }

        is UpdateStatus.Downloading -> {
            val info = updateStatus.updateInfo
            AlertDialog(
                onDismissRequest = { /* Modal during active download */ },
                title = { Text("Λήψη Ενημέρωσης v${info.latestVersion}") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { updateStatus.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${updateStatus.progressPercent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (updateStatus.totalBytes > 0) {
                                val downloadedMb = updateStatus.bytesDownloaded / (1024f * 1024f)
                                val totalMb = updateStatus.totalBytes / (1024f * 1024f)
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", downloadedMb)} / ${String.format(Locale.US, "%.1f", totalMb)} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = "Παρακαλώ περιμένετε καθώς ολοκληρώνεται η ασφαλής λήψη του νέου πακέτου APK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateStatus.ReadyToInstall -> {
            val info = updateStatus.updateInfo
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = { Text("Η Λήψη Ολοκληρώθηκε!") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Το πακέτο v${info.latestVersion} είναι έτοιμο για εγκατάσταση.")
                        if (!canRequestInstall) {
                            Text(
                                text = "Απαιτείται άδεια εγκατάστασης από άγνωστες πηγές.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    if (!canRequestInstall) {
                        Button(onClick = onOpenSettings) {
                            Text("Ενεργοποίηση Άδειας")
                        }
                    } else {
                        Button(onClick = { onInstall(updateStatus.apkFile) }) {
                            Text("Εγκατάσταση Τώρα")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Κλείσιμο")
                    }
                }
            )
        }

        is UpdateStatus.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Σφάλμα Ενημέρωσης") },
                text = { Text(updateStatus.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Εντάξει")
                    }
                }
            )
        }

        else -> {}
    }
}
