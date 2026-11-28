package com.example.thesis.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.thesis.R
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.repository.ConversationPreview
import com.example.thesis.repository.GroupManagementPolicy
import com.example.thesis.ui.util.ChatFormatting
import com.example.thesis.xmtp.cachedGroupName
import org.xmtp.android.library.Conversation

@Composable
internal fun InboxDashboardScreen(
    conversations: List<Conversation>,
    previews: Map<String, ConversationPreview> = emptyMap(),
    unreadCounts: Map<String, Int> = emptyMap(),
    nicknameSnapshot: NicknameSnapshot = NicknameSnapshot(),
    searchQuery: String = "",
    onSelectConversation: (Conversation) -> Unit,
    onEditContactNickname: (Conversation) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val trimmedQuery = searchQuery.trim()
    val filteredEntries = remember(conversations, previews, nicknameSnapshot, trimmedQuery) {
        conversations.mapIndexed { idx, conv ->
            val isDm = conv is Conversation.Dm
            val isLoopback = conv is Conversation.Group &&
                GroupManagementPolicy.isLoopbackGroupName(conv.group.cachedGroupName())
            val contactKey = if (isDm) conv.dm.peerInboxId else conv.id
            val fallbackLabel = when {
                isDm -> {
                    val peer = conv.dm.peerInboxId
                    if (peer.length >= 16) "${peer.take(6)}…${peer.takeLast(4)}" else peer
                }
                conv is Conversation.Group -> "Ομάδα ${conv.id.take(6)}…"
                else -> "Συνομιλία ${idx + 1}"
            }
            val groupName = (conv as? Conversation.Group)?.group?.cachedGroupName()
            val resolvedTitle = NicknamePolicy.resolveConversationTitle(
                conversationKey = contactKey,
                isDm = isDm,
                isLoopback = isLoopback,
                groupName = groupName,
                fallbackMaskedPeer = fallbackLabel,
                snapshot = nicknameSnapshot
            ).let { rawTitle ->
                // Limpiamos el prefijo "DM: " del titulo por defecto para una vista de contactos mas natural.
                if (isDm && rawTitle.startsWith("DM: ")) rawTitle.removePrefix("DM: ") else rawTitle
            }
            Triple(conv, resolvedTitle, contactKey)
        }.filter { (_, title, contactKey) ->
            if (trimmedQuery.isEmpty()) {
                true
            } else {
                title.contains(trimmedQuery, ignoreCase = true) ||
                    contactKey.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (filteredEntries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chat_bubble),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (trimmedQuery.isNotEmpty()) {
                        "Δεν βρέθηκαν συνομιλίες"
                    } else {
                        "Καμία συνομιλία ακόμη"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (trimmedQuery.isNotEmpty()) {
                        "Δοκιμάστε άλλο όνομα ή αναγνωριστικό επαφής."
                    } else {
                        "Πατήστε + για να ξεκινήσετε μια κρυπτογραφημένη συνομιλία."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)
            ) {
                itemsIndexed(filteredEntries, key = { _, item -> item.first.id }) { index, (conv, title, _) ->
                    val isLoopback = conv is Conversation.Group &&
                        GroupManagementPolicy.isLoopbackGroupName(conv.group.cachedGroupName())
                    ConversationListRow(
                        title = title,
                        isDm = conv is Conversation.Dm,
                        isLoopback = isLoopback,
                        preview = previews[conv.id],
                        unreadCount = unreadCounts[conv.id] ?: 0,
                        nicknameSnapshot = nicknameSnapshot,
                        onClick = { onSelectConversation(conv) },
                        // Reservamos el alias local por pulsacion larga solo para contactos 1 a 1.
                        onLongClick = if (conv is Conversation.Dm) {
                            { onEditContactNickname(conv) }
                        } else null
                    )
                    if (index < filteredEntries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListRow(
    title: String,
    isDm: Boolean,
    isLoopback: Boolean,
    preview: ConversationPreview?,
    unreadCount: Int,
    nicknameSnapshot: NicknameSnapshot,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val initials = remember(title, isLoopback) {
        if (isLoopback) "LB" else ChatFormatting.extractAvatarInitials(title)
    }
    val timeLabel = remember(preview?.timestampMs) {
        preview?.let { ChatFormatting.formatConversationListTime(it.timestampMs) }.orEmpty()
    }
    val subtitleText = when {
        preview != null && preview.isFromMe -> "Εσείς: ${preview.text}"
        preview != null && !isDm && !isLoopback && preview.senderInboxId.isNotBlank() ->
            "${GroupManagementPolicy.resolveSenderDisplayLabel(preview.senderInboxId, nicknameSnapshot)}: ${preview.text}"
        preview != null -> preview.text
        isLoopback -> "Τοπική συνεδρία δοκιμής"
        isDm -> "Κρυπτογραφημένη συνομιλία"
        else -> "Ομαδική συνομιλία"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val avatarContentColor = if (unreadCount > 0) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(
            shape = CircleShape,
            color = if (unreadCount > 0) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (!isDm && !isLoopback) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_group),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = avatarContentColor
                    )
                } else {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleSmall,
                        color = avatarContentColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (timeLabel.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unreadCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    color = if (unreadCount > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
