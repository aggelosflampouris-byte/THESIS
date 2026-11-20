package com.example.thesis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.R
import com.example.thesis.repository.ReadReceiptPolicy
import org.xmtp.android.library.libxmtp.DecodedMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MessagingChatScreen(
    messages: List<DecodedMessage>,
    localInboxId: String,
    peerLastReadTimestampMs: Long? = null,
    draft: String,
    isSending: Boolean,
    isSyncing: Boolean = false,
    isShredding: Boolean = false,
    onSync: () -> Unit = {},
    onDraftChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onRetryMessage: (String) -> Unit = onSendMessage
) {
    val listState = rememberLazyListState()
    val lastSeenOwnMessageId = remember(messages, localInboxId, peerLastReadTimestampMs) {
        messages.lastOrNull { msg ->
            ReadReceiptPolicy.isMessageSeenByPeer(
                message = msg,
                localInboxId = localInboxId,
                peerLastReadTimestampMs = peerLastReadTimestampMs
            )
        }?.id
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .imePadding()
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                val maxBubbleWidth = minOf(maxWidth * 0.82f, 360.dp)

                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Δεν υπάρχουν μηνύματα",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
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
                                    text = if (isSyncing) "Συγχρονισμός..." else "Συγχρονισμός",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            messages,
                            key = { it.id }
                        ) { message ->
                            val isOwn = message.senderInboxId == localInboxId
                            val isSeen = isOwn && ReadReceiptPolicy.isMessageSeenByPeer(
                                message = message,
                                localInboxId = localInboxId,
                                peerLastReadTimestampMs = peerLastReadTimestampMs
                            )
                            MessagingMessageBubble(
                                message = message,
                                isOwnMessage = isOwn,
                                isSeen = isSeen,
                                isLastSeenOwnMessage = isSeen && message.id == lastSeenOwnMessageId,
                                seenAtMs = peerLastReadTimestampMs,
                                maxBubbleWidth = maxBubbleWidth,
                                retryEnabled = !isSending && !isShredding,
                                onRetry = { onRetryMessage(message.body) }
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                        enabled = !isSending && !isShredding,
                        shape = RoundedCornerShape(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val content = draft.trim()
                            if (content.isNotEmpty()) onSendMessage(content)
                        },
                        enabled = draft.isNotBlank() && !isSending && !isShredding,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
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
    }
}

@Composable
private fun MessagingMessageBubble(
    message: DecodedMessage,
    isOwnMessage: Boolean,
    isSeen: Boolean = false,
    isLastSeenOwnMessage: Boolean = false,
    seenAtMs: Long? = null,
    maxBubbleWidth: Dp,
    retryEnabled: Boolean = true,
    onRetry: () -> Unit
) {
    val timeLabel = remember(message.sentAt, message.sentAtNs) {
        val date = message.sentAt ?: if (message.sentAtNs > 0L) Date(message.sentAtNs / 1_000_000L) else Date()
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    }
    val seenTimeLabel = remember(seenAtMs) {
        seenAtMs?.takeIf { it > 0L }?.let {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        }
    }
    val status = when (message.deliveryStatus) {
        DecodedMessage.MessageDeliveryStatus.FAILED -> "Δεν στάλθηκε"
        DecodedMessage.MessageDeliveryStatus.UNPUBLISHED -> "Αποστολή…"
        DecodedMessage.MessageDeliveryStatus.PUBLISHED -> when {
            isSeen && isLastSeenOwnMessage && seenTimeLabel != null -> "Διαβάστηκε $seenTimeLabel"
            isSeen -> "Διαβάστηκε"
            else -> "Στάλθηκε"
        }
        DecodedMessage.MessageDeliveryStatus.ALL -> null
    }
    val isFailed = message.deliveryStatus == DecodedMessage.MessageDeliveryStatus.FAILED
    val contentColor = if (isOwnMessage) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 60.dp, max = maxBubbleWidth),
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
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor
                )
                if (isOwnMessage && status != null) {
                    val isPublished = message.deliveryStatus == DecodedMessage.MessageDeliveryStatus.PUBLISHED
                    val statusTint = when {
                        isFailed -> MaterialTheme.colorScheme.error
                        isSeen -> MaterialTheme.colorScheme.primary
                        else -> contentColor.copy(alpha = 0.75f)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.75f),
                            fontSize = 10.sp
                        )
                        if (isPublished && isSeen) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_check_double),
                                contentDescription = "Διαβάστηκε",
                                modifier = Modifier.size(14.dp),
                                tint = statusTint
                            )
                        } else if (isPublished) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Στάλθηκε",
                                modifier = Modifier.size(13.dp),
                                tint = statusTint
                            )
                        }
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSeen) FontWeight.SemiBold else FontWeight.Normal,
                            color = statusTint,
                            fontSize = 10.sp
                        )
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.75f),
                        fontSize = 10.sp
                    )
                }
            }
        }
        if (isOwnMessage && isFailed) {
            TextButton(onClick = onRetry, enabled = retryEnabled) {
                Text("Προσπάθεια ξανά", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
    }
}
