package com.example.thesis.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.security.ScreenSecurityManager
import com.example.thesis.ui.util.QrCodeGenerator
import com.example.thesis.updater.UpdateConfig
import com.example.thesis.updater.UpdateStatus
import kotlin.math.ceil
import kotlin.math.floor

@Composable
internal fun QrCodeView(
    content: String,
    modifier: Modifier = Modifier,
    quietZone: Dp = 10.dp
) {
    val matrix = remember(content) { runCatching { QrCodeGenerator.encode(content) }.getOrNull() }

    // Mantenemos fondo blanco puro y contraste alto para garantizar lectura en tema claro y oscuro.
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        if (matrix == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(quietZone),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Μη διαθέσιμο QR",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF111111),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(quietZone)
            ) {
                val moduleCount = matrix.size
                val side = minOf(size.width, size.height)
                val originX = (size.width - side) / 2f
                val originY = (size.height - side) / 2f
                val cellSize = side / moduleCount
                val darkColor = Color(0xFF111111)

                for (row in 0 until moduleCount) {
                    // Ajustamos bordes a enteros de pixel para evitar grietas por antialiasing fraccionario.
                    val top = floor(originY + row * cellSize)
                    val bottom = ceil(originY + (row + 1) * cellSize)
                    for (col in 0 until moduleCount) {
                        if (matrix.isDark(row, col)) {
                            val left = floor(originX + col * cellSize)
                            val right = ceil(originX + (col + 1) * cellSize)
                            drawRect(
                                color = darkColor,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LatestVersionQrCard(
    currentVersion: String,
    updateStatus: UpdateStatus,
    modifier: Modifier = Modifier,
    downloadUrl: String = UpdateConfig.LATEST_APK_DOWNLOAD_URL,
    onCopyUrl: () -> Unit,
    onOpenDownloadUrl: () -> Unit,
    onExpandQr: () -> Unit
) {
    val versionLabel = if (updateStatus is UpdateStatus.UpdateAvailable) {
        "Νέα v${updateStatus.updateInfo.latestVersion}"
    } else {
        "v$currentVersion"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Λήψη Τελευταίας Έκδοσης",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = versionLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 340.dp) {
                    val qrSize = minOf(maxWidth - 40.dp, 168.dp).coerceAtLeast(120.dp)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QrCodeView(
                            content = downloadUrl,
                            modifier = Modifier
                                .size(qrSize)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onExpandQr)
                        )
                        Text(
                            text = "Σαρώστε από άλλη συσκευή για άμεση λήψη του νεότερου APK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "github.com/.../releases/latest",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = onExpandQr,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Μεγέθυνση", fontSize = 11.sp, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onCopyUrl,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Αντιγραφή URL", fontSize = 11.sp, maxLines = 1)
                            }
                            TextButton(
                                onClick = onOpenDownloadUrl,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Άνοιγμα", fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        QrCodeView(
                            content = downloadUrl,
                            modifier = Modifier
                                .size(124.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onExpandQr)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Σαρώστε από άλλη συσκευή για άμεση λήψη του νεότερου APK.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "github.com/.../releases/latest",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Πατήστε το QR για μεγέθυνση",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onCopyUrl,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Αντιγραφή URL", fontSize = 11.sp, maxLines = 1)
                                }
                                TextButton(
                                    onClick = onOpenDownloadUrl,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text("Άνοιγμα", fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun QrDownloadFullDialog(
    currentVersion: String,
    updateStatus: UpdateStatus,
    downloadUrl: String = UpdateConfig.LATEST_APK_DOWNLOAD_URL,
    onCopyUrl: () -> Unit,
    onOpenDownloadUrl: () -> Unit,
    onDismiss: () -> Unit
) {
    val versionSummary = if (updateStatus is UpdateStatus.UpdateAvailable) {
        "Εγκατεστημένη: v$currentVersion • Διαθέσιμη: v${updateStatus.updateInfo.latestVersion}"
    } else {
        "Εγκατεστημένη έκδοση: v$currentVersion"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
        title = { Text("Λήψη Τελευταίας Έκδοσης (QR)") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = versionSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val qrSize = minOf(maxWidth - 16.dp, 240.dp).coerceAtLeast(160.dp)
                    QrCodeView(
                        content = downloadUrl,
                        modifier = Modifier.size(qrSize),
                        quietZone = 14.dp
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = downloadUrl,
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = onCopyUrl) {
                        Text("Αντιγραφή URL", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onOpenDownloadUrl) {
                        Text("Άνοιγμα", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Κλείσιμο")
            }
        }
    )
}
