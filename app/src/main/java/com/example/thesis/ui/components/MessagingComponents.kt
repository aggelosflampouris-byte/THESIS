package com.example.thesis.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.updater.UpdateStatus
import com.example.thesis.xmtp.XmtpEngineState
import org.xmtp.android.library.Conversation
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessagingTopBar(
    selectedConversation: Conversation?,
    walletAddress: String?,
    engineState: XmtpEngineState,
    isSyncing: Boolean,
    isShredding: Boolean = false,
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenUpdate: () -> Unit = {},
    onOpenIdentity: () -> Unit,
    onOpenShred: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenProtocolInspector: () -> Unit = {}
) {
    TopAppBar(
        title = {
            if (selectedConversation != null) {
                val headerTitle = when (selectedConversation) {
                    is Conversation.Dm -> {
                        val peer = selectedConversation.dm.peerInboxId
                        val masked = if (peer.length >= 14) "${peer.take(6)}...${peer.takeLast(4)}" else peer
                        "MLS DM: $masked"
                    }
                    is Conversation.Group -> {
                        val name = selectedConversation.group.name
                        if (name.isNotBlank()) name else "MLS Chat: ${selectedConversation.topic.take(16)}..."
                    }
                }

                Column(
                    modifier = Modifier.clickable { onOpenProtocolInspector() }
                ) {
                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "MLS End-to-End Encrypted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
            if (selectedConversation != null) {
                IconButton(onClick = onOpenProtocolInspector) {
                    Icon(Icons.Default.Info, contentDescription = "Protocol Info")
                }

                if (engineState is XmtpEngineState.Ready) {
                    val rotation by animateFloatAsState(
                        targetValue = if (isSyncing) 360f else 0f,
                        label = "SyncRotation"
                    )
                    IconButton(onClick = onSync, enabled = !isSyncing && !isShredding) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Συγχρονισμός",
                            modifier = Modifier.rotate(rotation)
                        )
                    }
                }

                IconButton(onClick = onOpenLogs) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Logs & Telemetry")
                }
            } else {
                if (engineState is XmtpEngineState.Ready) {
                    val rotation by animateFloatAsState(
                        targetValue = if (isSyncing) 360f else 0f,
                        label = "SyncRotation"
                    )
                    IconButton(onClick = onSync, enabled = !isSyncing && !isShredding) {
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
                        Text("Update v${updateStatus.updateInfo.latestVersion}", fontSize = 11.sp)
                    }
                }

                if (walletAddress != null) {
                    TextButton(onClick = onOpenIdentity) {
                        Text("Ταυτότητα", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onOpenShred,
                        enabled = !isShredding,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("GDPR Shred", fontSize = 12.sp)
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
internal fun MessagingNotice(title: String, text: String) {
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

@Composable
internal fun SecurityEngineStatusCard(
    engineState: XmtpEngineState,
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
                StatusBadge(icon = Icons.Default.Lock, text = "SQLCipher 4.6.1", modifier = Modifier.weight(1f))
                StatusBadge(icon = Icons.Default.Build, text = "Keystore KEK", modifier = Modifier.weight(1f))
                StatusBadge(icon = Icons.Default.Refresh, text = "MLS Ratchet", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusBadge(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun messagingStatusText(message: String): String {
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
