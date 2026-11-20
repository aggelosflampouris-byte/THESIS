package com.example.thesis.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.xmtp.XmtpEngineState
import org.xmtp.android.library.Conversation
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessagingTopBar(
    selectedConversation: Conversation?,
    walletAddress: String?,
    nicknameSnapshot: NicknameSnapshot = NicknameSnapshot(),
    engineState: XmtpEngineState,
    isSyncing: Boolean,
    isShredding: Boolean = false,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenShred: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenProtocolInspector: () -> Unit = {}
) {
    TopAppBar(
        title = {
            if (selectedConversation != null) {
                val isDm = selectedConversation is Conversation.Dm
                val isLoopback = selectedConversation is Conversation.Group &&
                    selectedConversation.group.name == "MLS Research Loopback"
                val contactKey = if (isDm) selectedConversation.dm.peerInboxId else selectedConversation.id
                val maskedPeer = if (isDm) {
                    val peer = selectedConversation.dm.peerInboxId
                    if (peer.length >= 14) "${peer.take(6)}...${peer.takeLast(4)}" else peer
                } else {
                    selectedConversation.topic.take(16)
                }
                val groupName = (selectedConversation as? Conversation.Group)?.group?.name
                val resolvedNick = NicknamePolicy.resolveContactNickname(contactKey, nicknameSnapshot)
                val headerTitle = when {
                    resolvedNick != null -> resolvedNick
                    isDm -> "MLS DM: $maskedPeer"
                    isLoopback -> "MLS Research Loopback"
                    !groupName.isNullOrBlank() -> groupName
                    else -> "MLS Chat: ${selectedConversation.topic.take(16)}..."
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
                    Text(
                        text = "XMTP v4 (MLS)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val myNick = nicknameSnapshot.myNickname
                    val subtitleText = when {
                        !myNick.isNullOrBlank() && walletAddress != null ->
                            "$myNick • ${walletAddress.take(6)}...${walletAddress.takeLast(4)}"
                        !myNick.isNullOrBlank() -> myNick
                        walletAddress != null -> "ID: ${walletAddress.take(8)}...${walletAddress.takeLast(4)}"
                        else -> "SQLCipher • RFC 9420"
                    }
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (walletAddress != null && myNick.isNullOrBlank()) FontFamily.Monospace else FontFamily.Default
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                // Limitamos la barra superior a tres IconButtons compactos para evitar recortes del titulo en pantallas estrechas.
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

                if (walletAddress != null) {
                    IconButton(onClick = onOpenIdentity) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Ταυτότητα"
                        )
                    }

                    IconButton(
                        onClick = onOpenShred,
                        enabled = !isShredding
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "GDPR Shred",
                            tint = if (isShredding) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
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
internal fun UpdateAvailableBanner(
    latestVersion: String,
    onOpenUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Διαθέσιμη νέα έκδοση v$latestVersion",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onOpenUpdate,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Ενημέρωση", fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
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
    isScreenProtectionEnabled: Boolean = true,
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "DEV Network",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusBadge(icon = Icons.Default.Lock, text = "SQLCipher 4.6.1")
                StatusBadge(icon = Icons.Default.Build, text = "Keystore KEK")
                StatusBadge(icon = Icons.Default.Refresh, text = "MLS Ratchet")
                if (isScreenProtectionEnabled) {
                    StatusBadge(icon = Icons.Default.Lock, text = "Anti-Capture")
                }
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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

@Composable
internal fun ScreenRecordingWarningBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Εντοπίστηκε εγγραφή οθόνης — η προστασία οθόνης είναι απενεργοποιημένη",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun ScreenRecordingShieldOverlay(modifier: Modifier = Modifier) {
    // Bloqueamos toques y ocultamos visualmente el contenido mientras el sistema reporta grabacion de pantalla activa.
    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Προστασία Απορρήτου Ενεργή",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Εντοπίστηκε ενεργή εγγραφή οθόνης. Το περιεχόμενο έχει αποκρυφτεί (FLAG_SECURE).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
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
