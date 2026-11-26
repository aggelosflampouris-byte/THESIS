package com.example.thesis.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.diagnostics.ActionLogSession
import com.example.thesis.diagnostics.ActionLogStore
import com.example.thesis.security.CryptoAuditReport
import com.example.thesis.ui.ActionLogEntry
import com.example.thesis.ui.util.copyToClipboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogsTelemetryScreen(
    logs: List<ActionLogEntry>,
    sessions: List<ActionLogSession> = emptyList(),
    walletAddress: String? = null,
    inboxId: String? = null,
    installationId: String? = null,
    verifiedMsgCount: Int = 0,
    isBenchmarking: Boolean = false,
    cryptoAuditReport: CryptoAuditReport? = null,
    isRunningCryptoAudit: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit = {},
    onShareReport: () -> Unit = {},
    onSharePdfReport: () -> Unit = {},
    onRunCryptoAudit: () -> Unit = {},
    onRunBatchBenchmark: () -> Unit = {},
    onEmitBlockchainPoC: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTagFilter by rememberSaveable { mutableStateOf("ALL") }
    var selectedSessionFilter by rememberSaveable { mutableStateOf("ALL") }
    var collapsedSessionIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var isPoCCardExpanded by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current

    val effectiveSessions = remember(sessions, logs) {
        if (sessions.isNotEmpty()) {
            sessions
        } else {
            val latestSid = logs.lastOrNull()?.sessionId.orEmpty()
            ActionLogStore.buildSessions(
                entries = logs,
                currentSessionId = latestSid,
                currentSessionStartedAt = logs.lastOrNull()?.sessionStartedAt ?: System.currentTimeMillis()
            )
        }
    }

    val visibleSessions = remember(effectiveSessions, selectedSessionFilter) {
        if (selectedSessionFilter == "ALL") {
            effectiveSessions
        } else {
            effectiveSessions.filter { it.sessionId == selectedSessionFilter }.ifEmpty { effectiveSessions }
        }
    }

    val metricCount = remember(logs) { logs.count { it.tag.equals("METRIC", ignoreCase = true) } }
    val errorCount = remember(logs) { logs.count { it.isError } }
    val avgLatency = remember(logs) {
        val latencies = logs.mapNotNull { it.durationMs }
        if (latencies.isNotEmpty()) latencies.average() else null
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val sessionDateFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
    val filterTags = listOf(
        "ALL", "AUDIT", "CRASH", "CALL", "NOTIF", "METRIC", "E2EE",
        "MLS", "KEYSTORE", "SYNC", "BENCHMARK", "GDPR", "UPDATE"
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Action & Telemetry Log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${effectiveSessions.size} συνεδρίες • ${logs.size} συμβάντα • RFC 9420",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                    }
                },
                actions = {
                    TextButton(
                        onClick = onShareReport,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(".md", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    TextButton(
                        onClick = onSharePdfReport,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(".pdf", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    TextButton(
                        onClick = onExport,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Copy", fontSize = 11.sp, maxLines = 1)
                    }
                    if (logs.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedSessionFilter = "ALL"
                                collapsedSessionIds = emptySet()
                                onClear()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            // Unificamos cabecera, metricas y sesiones en un solo LazyColumn para evitar aplastamiento vertical en pantallas compactas.
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "poc_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isPoCCardExpanded = !isPoCCardExpanded },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Blockchain & MLS Chain of Custody",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "EIP-191 Identity • RFC 9420 TreeKEM Ledger",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (isPoCCardExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isPoCCardExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))

                                PoCIdentifierRow(
                                    label = "Ethereum (L1)",
                                    value = walletAddress ?: "0x (Αναμονή αρχικοποίησης...)",
                                    onCopy = {
                                        walletAddress?.let {
                                            copyToClipboard(context, "Ethereum Address", it)
                                            Toast.makeText(context, "Το Ethereum 0x Address αντιγράφηκε", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                PoCIdentifierRow(
                                    label = "XMTP Inbox ID",
                                    value = inboxId ?: "Αναμονή δικτύου...",
                                    onCopy = {
                                        inboxId?.let {
                                            copyToClipboard(context, "XMTP Inbox ID", it)
                                            Toast.makeText(context, "Το XMTP Inbox ID αντιγράφηκε", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                PoCIdentifierRow(
                                    label = "Installation ID",
                                    value = installationId ?: "Αναμονή MLS leaf node...",
                                    onCopy = {
                                        installationId?.let {
                                            copyToClipboard(context, "MLS Installation ID", it)
                                            Toast.makeText(context, "Το MLS Installation ID αντιγράφηκε", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Επαληθευμένα Network Msg IDs: $verifiedMsgCount",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Text(
                                        text = "Tap row to copy full ID",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "metrics_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "MLS Performance & Ratchet Metrics",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Sessions: ${effectiveSessions.size}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Metrics: $metricCount",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Errors: $errorCount",
                                    fontSize = 11.sp,
                                    color = if (errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                avgLatency?.let { avg ->
                                    Text(
                                        text = String.format(Locale.US, "Avg: %.1f ms", avg),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = onEmitBlockchainPoC,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("PoC IDs", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Button(
                                    onClick = onRunBatchBenchmark,
                                    enabled = !isBenchmarking && !isRunningCryptoAudit,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    if (isBenchmarking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Running...", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Batch (10x)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = onRunCryptoAudit,
                                enabled = !isRunningCryptoAudit && !isBenchmarking,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 40.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                if (isRunningCryptoAudit) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Εκτέλεση ελέγχων...", fontSize = 11.sp, maxLines = 1)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Verify Crypto & Network (7 Checks)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                if (cryptoAuditReport != null) {
                    item(key = "crypto_audit_card") {
                        val isAllPass = cryptoAuditReport.allPassed
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isAllPass) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                }
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isAllPass) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (isAllPass) Icons.Default.CheckCircle else Icons.Default.Warning,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isAllPass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Crypto & Network Audit (${cryptoAuditReport.passedCount}/${cryptoAuditReport.totalCount} PASS)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                    ) {
                                        Text(
                                            text = "${cryptoAuditReport.environmentName} • ${if (cryptoAuditReport.isDebugBuild) "DEBUG" else "RELEASE"}",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                cryptoAuditReport.checks.forEach { check ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${if (check.passed) "[PASS]" else "[FAIL]"} ${check.title}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (check.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = String.format(Locale.US, "%.1f ms", check.durationMs),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = check.details,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    cryptoAuditReport.eip191Proof?.let { proof ->
                                        OutlinedButton(
                                            onClick = {
                                                val proofText = buildString {
                                                    appendLine("Address: ${proof.walletAddress}")
                                                    appendLine("Challenge: ${proof.challenge}")
                                                    appendLine("Signature: ${proof.signatureHex}")
                                                }.trimEnd()
                                                copyToClipboard(context, "EIP-191 Proof", proofText, sensitive = false)
                                                Toast.makeText(context, "Το EIP-191 proof αντιγράφηκε", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Copy EIP-191 Proof", fontSize = 10.sp)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = onSharePdfReport,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Export PDF", fontSize = 10.sp)
                                    }
                                    OutlinedButton(
                                        onClick = onShareReport,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Export .md", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "session_filter_chips") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item(key = "session_chip_all") {
                            FilterChip(
                                selected = selectedSessionFilter == "ALL",
                                onClick = { selectedSessionFilter = "ALL" },
                                label = {
                                    Text(
                                        text = "Όλες οι συνεδρίες (${effectiveSessions.size})",
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                        items(effectiveSessions, key = { "session_chip_${it.sessionId}" }) { session ->
                            val chipLabel = buildString {
                                if (session.isCurrent) {
                                    append("Τρέχουσα #${session.sessionNumber}")
                                } else {
                                    append("Συνεδρία #${session.sessionNumber}")
                                }
                                append(" (${session.entries.size})")
                                if (session.hasCrash) {
                                    append(" • CRASH")
                                }
                            }
                            FilterChip(
                                selected = selectedSessionFilter == session.sessionId,
                                onClick = { selectedSessionFilter = session.sessionId },
                                label = {
                                    Text(
                                        text = chipLabel,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        color = if (session.hasCrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            )
                        }
                    }
                }

                item(key = "filter_chips") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filterTags) { tag ->
                            FilterChip(
                                selected = selectedTagFilter == tag,
                                onClick = { selectedTagFilter = tag },
                                label = { Text(tag, fontSize = 11.sp, maxLines = 1) }
                            )
                        }
                    }
                }

                for (session in visibleSessions) {
                    val sessionFilteredEntries = if (selectedTagFilter == "ALL") {
                        session.entries
                    } else {
                        session.entries.filter { it.tag.equals(selectedTagFilter, ignoreCase = true) }
                    }
                    val isCollapsed = session.sessionId in collapsedSessionIds

                    item(key = "session_header_${session.sessionId}") {
                        SessionHeaderCard(
                            session = session,
                            filteredCount = sessionFilteredEntries.size,
                            isCollapsed = isCollapsed,
                            sessionDateFormat = sessionDateFormat,
                            timeFormat = timeFormat,
                            onToggleExpand = {
                                collapsedSessionIds = if (isCollapsed) {
                                    collapsedSessionIds - session.sessionId
                                } else {
                                    collapsedSessionIds + session.sessionId
                                }
                            },
                            onCopySession = {
                                val text = formatSessionText(
                                    session = session,
                                    entries = sessionFilteredEntries,
                                    sessionDateFormat = sessionDateFormat,
                                    timeFormat = timeFormat
                                )
                                copyToClipboard(context, "Session #${session.sessionNumber} Logs", text)
                                Toast.makeText(
                                    context,
                                    "Η Συνεδρία #${session.sessionNumber} αντιγράφηκε στο πρόχειρο",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }

                    if (!isCollapsed) {
                        if (sessionFilteredEntries.isEmpty()) {
                            item(key = "session_empty_${session.sessionId}") {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (selectedTagFilter == "ALL") {
                                                "Δεν υπάρχουν ακόμη καταγραφές σε αυτή τη συνεδρία."
                                            } else {
                                                "Δεν υπάρχουν καταγραφές [$selectedTagFilter] στη Συνεδρία #${session.sessionNumber}."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            items(
                                items = sessionFilteredEntries.asReversed(),
                                key = { "${session.sessionId}_${it.id}" }
                            ) { entry ->
                                ActionLogEntryCard(
                                    entry = entry,
                                    sessionNumber = session.sessionNumber,
                                    timeFormat = timeFormat,
                                    onCopy = {
                                        val logText = "${timeFormat.format(Date(entry.timestamp))} [S#${session.sessionNumber}][${entry.tag}] ${entry.message}" +
                                            (entry.durationMs?.let { " (${String.format(Locale.US, "%.1f ms", it)})" } ?: "")
                                        copyToClipboard(context, "Telemetry Log", logText)
                                        Toast.makeText(context, "Αντιγράφηκε στο πρόχειρο", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeaderCard(
    session: ActionLogSession,
    filteredCount: Int,
    isCollapsed: Boolean,
    sessionDateFormat: SimpleDateFormat,
    timeFormat: SimpleDateFormat,
    onToggleExpand: () -> Unit,
    onCopySession: () -> Unit
) {
    val borderColor = when {
        session.hasCrash -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        session.isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = when {
        session.hasCrash -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
        session.isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (session.isCurrent) {
                            "Τρέχουσα Συνεδρία #${session.sessionNumber}"
                        } else {
                            "Προηγούμενη Συνεδρία #${session.sessionNumber}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (session.isCurrent) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        }
                    ) {
                        Text(
                            text = if (session.isCurrent) "ACTIVE" else "SAVED",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (session.isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (session.hasCrash) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "CRASH",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (session.errorCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "${session.errorCount} ERR",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.entries.isNotEmpty()) {
                        TextButton(
                            onClick = onCopySession,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Icon(
                        imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val startStr = if (session.startedAt > 0L) sessionDateFormat.format(Date(session.startedAt)) else "—"
            val endStr = if (session.endedAt > 0L) timeFormat.format(Date(session.endedAt)) else "—"
            Text(
                text = "Έναρξη: $startStr • Τελευταίο: $endStr • $filteredCount/${session.entries.size} συμβάντα",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionLogEntryCard(
    entry: ActionLogEntry,
    sessionNumber: Int,
    timeFormat: SimpleDateFormat,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = timeFormat.format(Date(entry.timestamp)),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = "S#$sessionNumber",
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (entry.tag.uppercase(Locale.ROOT)) {
                        "METRIC" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                        "E2EE", "MLS" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        "KEYSTORE" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        "GDPR", "CRASH" -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    }
                ) {
                    Text(
                        text = entry.tag,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (entry.isError) MaterialTheme.colorScheme.error
                        else when (entry.tag.uppercase(Locale.ROOT)) {
                            "METRIC" -> MaterialTheme.colorScheme.tertiary
                            "GDPR", "CRASH" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
                entry.durationMs?.let { ms ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1f ms", ms),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
            Text(
                text = entry.message,
                fontSize = 12.sp,
                color = if (entry.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatSessionText(
    session: ActionLogSession,
    entries: List<ActionLogEntry>,
    sessionDateFormat: SimpleDateFormat,
    timeFormat: SimpleDateFormat
): String {
    return buildString {
        val status = if (session.isCurrent) "ACTIVE" else "SAVED"
        val startStr = if (session.startedAt > 0L) sessionDateFormat.format(Date(session.startedAt)) else "—"
        appendLine("=== Session #${session.sessionNumber} [$status] (Started: $startStr, Events: ${entries.size}) ===")
        entries.forEach { entry ->
            val durationPart = entry.durationMs?.let { String.format(Locale.US, " (%.1f ms)", it) }.orEmpty()
            appendLine("${timeFormat.format(Date(entry.timestamp))} [${entry.tag}] ${entry.message}$durationPart")
        }
    }.trimEnd()
}

@Composable
private fun PoCIdentifierRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (value.length > 28) "${value.take(14)}...${value.takeLast(10)}" else value,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Copy",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
