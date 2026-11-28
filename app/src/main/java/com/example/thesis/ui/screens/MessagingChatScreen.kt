package com.example.thesis.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.R
import com.example.thesis.call.CallHistoryPolicy
import com.example.thesis.call.ChatCallEvent
import com.example.thesis.call.ChatCallOutcome
import com.example.thesis.repository.ReadReceiptPolicy
import com.example.thesis.repository.extractMediaAttachment
import com.example.thesis.repository.extractVoiceNote
import com.example.thesis.voice.VoicePlaybackState
import com.example.thesis.voice.VoiceRecordingState
import com.example.thesis.xmtp.MediaAttachment
import com.example.thesis.xmtp.MediaAttachmentCodec
import com.example.thesis.xmtp.VoiceNote
import com.example.thesis.xmtp.VoiceNoteCodec
import org.xmtp.android.library.libxmtp.DecodedMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface ChatTimelineItem {
    val itemKey: String
    val timestampMs: Long

    data class TextMessage(
        val message: DecodedMessage,
        override val timestampMs: Long
    ) : ChatTimelineItem {
        override val itemKey: String = "msg_${message.id}"
    }

    data class CallFeedback(
        val event: ChatCallEvent
    ) : ChatTimelineItem {
        override val itemKey: String = "call_${event.callId}"
        override val timestampMs: Long = event.startedAtMs
    }
}

@Composable
internal fun MessagingChatScreen(
    messages: List<DecodedMessage>,
    callEvents: List<ChatCallEvent> = emptyList(),
    peerDisplayName: String = "Επαφή",
    localInboxId: String,
    peerLastReadTimestampMs: Long? = null,
    draft: String,
    isSyncing: Boolean = false,
    isShredding: Boolean = false,
    isUploadingMedia: Boolean = false,
    voiceRecordingState: VoiceRecordingState = VoiceRecordingState(),
    voicePlaybackState: VoicePlaybackState = VoicePlaybackState(),
    onSync: () -> Unit = {},
    onStartVoiceCall: (() -> Unit)? = null,
    onStartVoiceRecording: () -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    onStopAndSendVoiceRecording: () -> Unit = {},
    onToggleVoicePlayback: (String, VoiceNote) -> Unit = { _, _ -> },
    onSeekVoicePlayback: (String, Float) -> Unit = { _, _ -> },
    onPickPhoto: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onPickDocument: () -> Unit = {},
    onOpenMediaAttachment: (MediaAttachment) -> Unit = {},
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

    // Intercalamos mensajes (texto y notas de voz) y eventos de llamada en una sola linea temporal cronologica.
    val timelineItems = remember(messages, callEvents) {
        val combined = ArrayList<ChatTimelineItem>(messages.size + callEvents.size)
        for (msg in messages) {
            val ts = msg.sentAt?.time ?: if (msg.sentAtNs > 0L) msg.sentAtNs / 1_000_000L else 0L
            combined.add(ChatTimelineItem.TextMessage(msg, ts))
        }
        for (call in callEvents) {
            combined.add(ChatTimelineItem.CallFeedback(call))
        }
        combined.sortedWith(
            compareBy<ChatTimelineItem> { it.timestampMs }
                .thenBy { it.itemKey }
        )
    }

    LaunchedEffect(timelineItems.size) {
        if (timelineItems.isNotEmpty()) {
            listState.animateScrollToItem(timelineItems.lastIndex)
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

                if (timelineItems.isEmpty()) {
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
                            timelineItems,
                            key = { it.itemKey }
                        ) { item ->
                            when (item) {
                                is ChatTimelineItem.TextMessage -> {
                                    val message = item.message
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
                                        retryEnabled = !isShredding,
                                        voicePlaybackState = voicePlaybackState,
                                        onToggleVoicePlayback = onToggleVoicePlayback,
                                        onSeekVoicePlayback = onSeekVoicePlayback,
                                        onOpenMediaAttachment = onOpenMediaAttachment,
                                        onRetry = { onRetryMessage(message.body) }
                                    )
                                }

                                is ChatTimelineItem.CallFeedback -> {
                                    CallFeedbackChatCard(
                                        event = item.event,
                                        peerDisplayName = peerDisplayName,
                                        onCallback = onStartVoiceCall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                if (voiceRecordingState.isRecording) {
                    VoiceRecordingInputBar(
                        recordingState = voiceRecordingState,
                        onCancel = onCancelVoiceRecording,
                        onSend = onStopAndSendVoiceRecording
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        IconButton(
                            onClick = onPickPhoto,
                            enabled = !isShredding && !isUploadingMedia,
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isUploadingMedia) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_photo_library),
                                    contentDescription = "Φωτογραφία από συλλογή",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onTakePhoto,
                            enabled = !isShredding && !isUploadingMedia,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_photo_camera),
                                contentDescription = "Κάμερα",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = onPickDocument,
                            enabled = !isShredding && !isUploadingMedia,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_attach_file),
                                contentDescription = "Επισύναψη αρχείου",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
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
                            // Mantenemos el campo habilitado durante el envio para no perder el foco ni cerrar el teclado.
                            enabled = !isShredding,
                            shape = RoundedCornerShape(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val hasDraftText = draft.isNotBlank()
                        IconButton(
                            onClick = {
                                if (hasDraftText) {
                                    val content = draft.trim()
                                    if (content.isNotEmpty()) onSendMessage(content)
                                } else {
                                    onStartVoiceRecording()
                                }
                            },
                            enabled = !isShredding,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            if (hasDraftText) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Αποστολή",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_mic_on),
                                    contentDescription = "Φωνητικό μήνυμα",
                                    tint = MaterialTheme.colorScheme.primary
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
private fun VoiceRecordingInputBar(
    recordingState: VoiceRecordingState,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val bars = remember(recordingState.livePeaks) {
        if (recordingState.livePeaks.isEmpty()) List(16) { 18 } else recordingState.livePeaks.takeLast(18)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Ακύρωση εγγραφής",
                tint = MaterialTheme.colorScheme.error
            )
        }

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Text(
                    text = "${VoiceNoteCodec.formatDuration(recordingState.elapsedMs)} / 2:00",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    bars.forEach { peak ->
                        val frac = (peak.coerceIn(10, 100) / 100f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((4f + frac * 16f).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
                        )
                    }
                }
            }
        }

        FilledIconButton(
            onClick = onSend,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Αποστολή φωνητικού"
            )
        }
    }
}

@Composable
private fun CallFeedbackChatCard(
    event: ChatCallEvent,
    peerDisplayName: String,
    onCallback: (() -> Unit)?
) {
    val isMissedOrDeclined = event.outcome == ChatCallOutcome.MISSED ||
        event.outcome == ChatCallOutcome.DECLINED ||
        event.outcome == ChatCallOutcome.BUSY
    val accentColor = if (isMissedOrDeclined) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val containerColor = if (isMissedOrDeclined) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    }
    val timeLabel = remember(event.startedAtMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.startedAtMs))
    }
    val title = remember(event) { CallHistoryPolicy.formatCallTitle(event) }
    val subtitle = remember(event, peerDisplayName) {
        CallHistoryPolicy.formatCallSubtitle(event, peerDisplayName)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 220.dp, max = 420.dp),
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.16f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(
                                when {
                                    isMissedOrDeclined -> R.drawable.ic_call_end
                                    event.isVideo -> R.drawable.ic_videocam
                                    else -> R.drawable.ic_call_phone
                                }
                            ),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isMissedOrDeclined) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                if (onCallback != null &&
                    event.outcome != ChatCallOutcome.RINGING &&
                    event.outcome != ChatCallOutcome.IN_PROGRESS
                ) {
                    TextButton(
                        onClick = onCallback,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Κλήση",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
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
    voicePlaybackState: VoicePlaybackState = VoicePlaybackState(),
    onToggleVoicePlayback: (String, VoiceNote) -> Unit = { _, _ -> },
    onSeekVoicePlayback: (String, Float) -> Unit = { _, _ -> },
    onOpenMediaAttachment: (MediaAttachment) -> Unit = {},
    onRetry: () -> Unit
) {
    val voiceNote = remember(message.id) { message.extractVoiceNote() }
    val mediaAttachment = remember(message.id) { message.extractMediaAttachment() }
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
            modifier = Modifier.widthIn(
                min = if (voiceNote != null || mediaAttachment != null) 220.dp else 60.dp,
                max = maxBubbleWidth
            ),
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
                if (voiceNote != null) {
                    val isCurrent = voicePlaybackState.activeMessageId == message.id
                    val isPlaying = isCurrent && voicePlaybackState.isPlaying
                    val activeDurMs = if (isCurrent && voicePlaybackState.durationMs > 0L) {
                        voicePlaybackState.durationMs
                    } else {
                        voiceNote.durationMs
                    }
                    val progressFraction = if (isCurrent && activeDurMs > 0L) {
                        (voicePlaybackState.positionMs.toFloat() / activeDurMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val bars = remember(voiceNote.waveformPeaks) {
                        if (voiceNote.waveformPeaks.isEmpty()) {
                            VoiceNoteCodec.downsampleWaveform(emptyList(), 28)
                        } else {
                            voiceNote.waveformPeaks
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledIconButton(
                            onClick = { onToggleVoicePlayback(message.id, voiceNote) },
                            modifier = Modifier.size(40.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            if (isPlaying) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_pause),
                                    contentDescription = "Παύση",
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Αναπαραγωγή",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(26.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                val totalBars = bars.size.coerceAtLeast(1)
                                bars.forEachIndexed { idx, peak ->
                                    val barPos = (idx + 0.5f) / totalBars
                                    val isPlayed = isCurrent && barPos <= progressFraction
                                    val barColor = if (isPlayed) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        contentColor.copy(alpha = 0.35f)
                                    }
                                    val barHeight = (4f + (peak.coerceIn(10, 100) / 100f) * 20f).dp
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(barHeight)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(barColor)
                                            .clickable(enabled = isCurrent) {
                                                onSeekVoicePlayback(message.id, barPos)
                                            }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val displayedTime = if (isCurrent && (isPlaying || voicePlaybackState.positionMs > 0L)) {
                                    "${VoiceNoteCodec.formatDuration(voicePlaybackState.positionMs)} / ${VoiceNoteCodec.formatDuration(activeDurMs)}"
                                } else {
                                    VoiceNoteCodec.formatDuration(activeDurMs)
                                }
                                Text(
                                    text = displayedTime,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = contentColor
                                )
                                Text(
                                    text = "MLS E2EE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = contentColor.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else if (mediaAttachment != null) {
                    val isImage = remember(mediaAttachment.mimeType) {
                        MediaAttachmentCodec.isImageMimeType(mediaAttachment.mimeType)
                    }
                    val sizeLabel = remember(mediaAttachment.dataBytes) {
                        MediaAttachmentCodec.formatByteSize(mediaAttachment.dataBytes.size)
                    }
                    val thumbnailBitmap = remember(message.id) {
                        if (isImage) decodeChatThumbnail(mediaAttachment.dataBytes) else null
                    }
                    if (thumbnailBitmap != null) {
                        Image(
                            bitmap = thumbnailBitmap,
                            contentDescription = mediaAttachment.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 230.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenMediaAttachment(mediaAttachment) }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📷 $sizeLabel",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor
                            )
                            Text(
                                text = "MLS E2EE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenMediaAttachment(mediaAttachment) },
                            shape = RoundedCornerShape(10.dp),
                            color = contentColor.copy(alpha = 0.08f)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_description),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mediaAttachment.fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = contentColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${mediaAttachment.mimeType} • $sizeLabel • MLS E2EE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = contentColor.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (mediaAttachment.caption.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = mediaAttachment.caption,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor
                        )
                    }
                } else {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor
                    )
                }

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
        if (isOwnMessage && isFailed && voiceNote == null && mediaAttachment == null) {
            TextButton(onClick = onRetry, enabled = retryEnabled) {
                Text("Προσπάθεια ξανά", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
    }
}

// Decodificamos miniaturas con submuestreo para reducir presion de memoria en listas largas.
private fun decodeChatThumbnail(bytes: ByteArray, targetPx: Int = 720): ImageBitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= targetPx) {
            longest /= 2
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}
