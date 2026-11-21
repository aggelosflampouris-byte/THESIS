package com.example.thesis.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.example.thesis.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.ui.ActionLogEntry
import com.example.thesis.ui.components.LatestVersionQrCard
import com.example.thesis.ui.components.SecurityEngineStatusCard
import com.example.thesis.updater.UpdateStatus
import com.example.thesis.xmtp.XmtpEngineState
import org.xmtp.android.library.Conversation

@Composable
internal fun InboxDashboardScreen(
    conversations: List<Conversation>,
    unreadCounts: Map<String, Int> = emptyMap(),
    nicknameSnapshot: NicknameSnapshot = NicknameSnapshot(),
    engineState: XmtpEngineState,
    isCreatingConversation: Boolean,
    isShredding: Boolean = false,
    actionLogs: List<ActionLogEntry>,
    currentVersion: String,
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    onOpenLogs: () -> Unit,
    onNewConversation: () -> Unit,
    onStartLoopback: () -> Unit,
    onSelectConversation: (Conversation) -> Unit,
    onEditContactNickname: (Conversation) -> Unit = {},
    onCopyDownloadUrl: () -> Unit = {},
    onOpenDownloadUrl: () -> Unit = {},
    onExpandQr: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // Unificamos el panel en un solo LazyColumn para que toda la superficie sea desplazable incluso en horizontal o con fuentes grandes.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "security_status") {
                SecurityEngineStatusCard(
                    engineState = engineState,
                )
            }

            item(key = "quick_actions") {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val actionsEnabled = !isCreatingConversation && !isShredding
                    if (maxWidth < 300.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onStartLoopback,
                                enabled = actionsEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Loopback MLS", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            OutlinedButton(
                                onClick = onNewConversation,
                                enabled = actionsEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                if (isCreatingConversation) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Νέα", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onStartLoopback,
                                enabled = actionsEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Loopback MLS", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            OutlinedButton(
                                onClick = onNewConversation,
                                enabled = actionsEnabled,
                                modifier = Modifier
                                    .weight(0.8f)
                                    .heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                if (isCreatingConversation) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Νέα", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "conversations_header") {
                Text(
                    text = "Ενεργές Συνομιλίες MLS (${conversations.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (conversations.isEmpty()) {
                item(key = "empty_conversations") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Δεν υπάρχουν ακόμη συνομιλίες",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Πατήστε 'Loopback MLS' για άμεση δημιουργία συνεδρίας και δοκιμή κρυπτογράφησης στη συσκευή σας.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(conversations, key = { _, conv -> conv.id }) { index, conv ->
                    ConversationItemCard(
                        index = index + 1,
                        conversation = conv,
                        unreadCount = unreadCounts[conv.id] ?: 0,
                        nicknameSnapshot = nicknameSnapshot,
                        onEditNickname = { onEditContactNickname(conv) },
                        onClick = { onSelectConversation(conv) }
                    )
                }
            }

            item(key = "telemetry_entry") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLogs),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.List,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Action & Telemetry Log",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${actionLogs.size} καταγραφές",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item(key = "qr_download_section") {
                LatestVersionQrCard(
                    currentVersion = currentVersion,
                    updateStatus = updateStatus,
                    onCopyUrl = onCopyDownloadUrl,
                    onOpenDownloadUrl = onOpenDownloadUrl,
                    onExpandQr = onExpandQr
                )
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ConversationItemCard(
    index: Int,
    conversation: Conversation,
    unreadCount: Int = 0,
    nicknameSnapshot: NicknameSnapshot = NicknameSnapshot(),
    onEditNickname: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDm = conversation is Conversation.Dm
    val isLoopback = conversation is Conversation.Group && conversation.group.name == "MLS Research Loopback"
    val contactKey = if (isDm) conversation.dm.peerInboxId else conversation.id
    val maskedPeer = when {
        isDm -> {
            val peer = conversation.dm.peerInboxId
            if (peer.length >= 16) "${peer.take(8)}...${peer.takeLast(6)}" else peer
        }
        conversation is Conversation.Group -> "Ομάδα MLS: ${conversation.id.take(8)}..."
        else -> "Συνομιλία $index: ${conversation.topic.take(18)}..."
    }
    val groupName = (conversation as? Conversation.Group)?.group?.name
    val title = NicknamePolicy.resolveConversationTitle(
        conversationKey = contactKey,
        isDm = isDm,
        isLoopback = isLoopback,
        groupName = groupName,
        fallbackMaskedPeer = maskedPeer,
        snapshot = nicknameSnapshot
    )
    val hasNickname = NicknamePolicy.resolveContactNickname(contactKey, nicknameSnapshot) != null

    val subtitle = when {
        isDm && hasNickname -> "DM • $maskedPeer"
        isDm -> "Direct Message"
        isLoopback -> "Τοπική δοκιμή συσκευής"
        else -> "Ομαδική συνομιλία"
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
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when {
                            isDm -> Icons.Default.Person
                            isLoopback -> Icons.Default.PlayArrow
                            else -> Icons.Default.AccountCircle
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = when {
                            isDm -> MaterialTheme.colorScheme.onPrimaryContainer
                            isLoopback -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "Topic: ${conversation.topic.take(10)}...",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (unreadCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_notification_message_badge),
                        contentDescription = "Νέο μήνυμα",
                        modifier = Modifier.size(22.dp)
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (!isLoopback) {
                IconButton(
                    onClick = onEditNickname,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Επεξεργασία ψευδωνύμου",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
