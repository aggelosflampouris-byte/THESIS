package com.example.thesis.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import com.example.thesis.ui.ActionLogEntry
import com.example.thesis.ui.util.copyToClipboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogsTelemetryScreen(
    logs: List<ActionLogEntry>,
    walletAddress: String? = null,
    inboxId: String? = null,
    installationId: String? = null,
    verifiedMsgCount: Int = 0,
    isBenchmarking: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit = {},
    onShareReport: () -> Unit = {},
    onRunBatchBenchmark: () -> Unit = {},
    onEmitBlockchainPoC: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTagFilter by rememberSaveable { mutableStateOf("ALL") }
    var isPoCCardExpanded by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current

    val filteredLogs = remember(logs, selectedTagFilter) {
        if (selectedTagFilter == "ALL") logs
        else logs.filter { it.tag.equals(selectedTagFilter, ignoreCase = true) }
    }

    val metricCount = remember(logs) { logs.count { it.tag.equals("METRIC", ignoreCase = true) } }
    val errorCount = remember(logs) { logs.count { it.isError } }
    val avgLatency = remember(logs) {
        val latencies = logs.mapNotNull { it.durationMs }
        if (latencies.isNotEmpty()) latencies.average() else null
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val filterTags = listOf("ALL", "METRIC", "E2EE", "MLS", "KEYSTORE", "SYNC", "BENCHMARK", "GDPR", "UPDATE")

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
                            text = "${logs.size} συμβάντα • RFC 9420 / SQLCipher",
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
                    IconButton(onClick = onShareReport) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Κοινοποίηση (.md)"
                        )
                    }
                    TextButton(
                        onClick = onExport,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Copy", fontSize = 11.sp, maxLines = 1)
                    }
                    if (logs.isNotEmpty()) {
                        TextButton(
                            onClick = onClear,
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
            // Unificamos cabecera, metricas y registros en un solo LazyColumn para evitar aplastamiento vertical en pantallas compactas.
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
                                    enabled = !isBenchmarking,
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

                if (filteredLogs.isEmpty()) {
                    item(key = "empty_logs") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Δεν υπάρχουν καταγραφές για το φίλτρο $selectedTagFilter.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(filteredLogs.reversed(), key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val logText = "${timeFormat.format(Date(entry.timestamp))} [${entry.tag}] ${entry.message}" +
                                        (entry.durationMs?.let { " (${String.format(Locale.US, "%.1f ms", it)})" } ?: "")
                                    copyToClipboard(context, "Telemetry Log", logText)
                                    Toast.makeText(context, "Αντιγράφηκε στο πρόχειρο", Toast.LENGTH_SHORT).show()
                                },
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
                                        color = when (entry.tag.uppercase(Locale.ROOT)) {
                                            "METRIC" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                            "E2EE", "MLS" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            "KEYSTORE" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                            "GDPR" -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
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
                                                "GDPR" -> MaterialTheme.colorScheme.error
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
                }
            }
        }
    }
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
