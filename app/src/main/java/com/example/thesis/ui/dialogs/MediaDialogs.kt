package com.example.thesis.ui.dialogs

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.thesis.R
import com.example.thesis.media.MediaViewLimitPolicy
import com.example.thesis.security.ScreenSecurityManager
import com.example.thesis.security.ScreenSecurityPolicy
import com.example.thesis.xmtp.MediaAttachment
import com.example.thesis.xmtp.MediaAttachmentCodec

// Menu modal que permite elegir entre imagen permanente o temporal antes de enviarla por MLS.
@Composable
internal fun MediaSendModeDialog(
    onSendPermanent: () -> Unit,
    onSendTemporary: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
        title = {
            Text(
                text = "Τρόπος αποστολής εικόνας",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Επιλέξτε αν η εικόνα θα παραμείνει στη συνομιλία ή αν θα ανοίγει προσωρινά.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                SendModeOptionRow(
                    title = "Μόνιμη εικόνα",
                    subtitle = "Θα εμφανίζεται κανονικά στη συνομιλία",
                    iconRes = R.drawable.ic_photo_library,
                    onClick = onSendPermanent
                )
                SendModeOptionRow(
                    title = "Προσωρινή εικόνα (${MediaViewLimitPolicy.DEFAULT_TEMPORARY_VIEWS} προβολές)",
                    subtitle = "Καλυμμένη μέχρι να την πατήσει ο παραλήπτης — κλειδώνει μετά από 2 προβολές",
                    badgeText = "${MediaViewLimitPolicy.DEFAULT_TEMPORARY_VIEWS}×",
                    onClick = onSendTemporary
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Ακύρωση")
            }
        }
    )
}

@Composable
private fun SendModeOptionRow(
    title: String,
    subtitle: String,
    iconRes: Int? = null,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (badgeText != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun MediaAttachmentViewerDialog(
    attachment: MediaAttachment,
    temporaryViewNumber: Int? = null,
    onSave: (MediaAttachment) -> Unit,
    onShare: (MediaAttachment) -> Unit,
    onDismiss: () -> Unit
) {
    val isImage = remember(attachment.mimeType) { MediaAttachmentCodec.isImageMimeType(attachment.mimeType) }
    val sizeLabel = remember(attachment.dataBytes) { MediaAttachmentCodec.formatByteSize(attachment.dataBytes.size) }
    val imageBitmap = remember(attachment) {
        if (!isImage) {
            null
        } else {
            runCatching {
                BitmapFactory.decodeByteArray(attachment.dataBytes, 0, attachment.dataBytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Conservamos proteccion anti-captura (FLAG_SECURE) tambien en el visor multimedia completo.
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            securePolicy = ScreenSecurityPolicy.DIALOG_SECURE_POLICY,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (isImage && imageBitmap != null) Color.Black else MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Surface(
                    color = if (isImage && imageBitmap != null) {
                        Color.Black.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val headerTint = if (isImage && imageBitmap != null) Color.White else MaterialTheme.colorScheme.onSurface
                        val subtitleTint = if (isImage && imageBitmap != null) {
                            Color.White.copy(alpha = 0.75f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Κλείσιμο",
                                tint = headerTint
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = headerTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val headerSubtitle = if (attachment.isTemporary) {
                                MediaViewLimitPolicy.formatViewerBanner(
                                    viewNumber = temporaryViewNumber ?: 1,
                                    maxViews = attachment.maxViews
                                )
                            } else {
                                "$sizeLabel • SHA-256: ${attachment.resolvedSha256Hex.take(12)}… • MLS E2EE"
                            }
                            Text(
                                text = headerSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = subtitleTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // En medios temporales deshabilitamos guardar y compartir para respetar el limite de vistas.
                        if (!attachment.isTemporary) {
                            IconButton(onClick = { onSave(attachment) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_download),
                                    contentDescription = "Αποθήκευση",
                                    tint = headerTint
                                )
                            }
                            IconButton(onClick = { onShare(attachment) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_share),
                                    contentDescription = "Κοινοποίηση",
                                    tint = headerTint
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isImage && imageBitmap != null) {
                        var scale by remember(attachment) { mutableFloatStateOf(1f) }
                        var offset by remember(attachment) { mutableStateOf(Offset.Zero) }
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = attachment.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(attachment) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                                        scale = nextScale
                                        offset = if (nextScale > 1f) {
                                            offset + pan
                                        } else {
                                            Offset.Zero
                                        }
                                    }
                                }
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_description),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = attachment.fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "${attachment.mimeType} • $sizeLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "SHA-256: ${attachment.resolvedSha256Hex}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (attachment.isTemporary) {
                                    Text(
                                        text = "Προσωρινό αρχείο MLS E2EE — απενεργοποιημένη αποθήκευση/κοινοποίηση",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { onSave(attachment) }) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_download),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Αποθήκευση")
                                        }
                                        OutlinedButton(onClick = { onShare(attachment) }) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_share),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Κοινοποίηση")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (attachment.caption.isNotBlank()) {
                    Surface(
                        color = if (isImage && imageBitmap != null) {
                            Color.Black.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = attachment.caption,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isImage && imageBitmap != null) Color.White else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

