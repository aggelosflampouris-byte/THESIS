package com.example.thesis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.thesis.updater.UpdateStatus

// Pantalla plana estilo ajustes nativos de Android: agrupa perfil, app, laboratorio y borrado seguro.
@Composable
internal fun SettingsScreen(
    myNickname: String?,
    walletAddress: String?,
    inboxId: String?,
    currentVersion: String,
    updateStatus: UpdateStatus,
    notificationsEnabled: Boolean,
    isServiceNotificationVisible: Boolean,
    logsCount: Int,
    isCreatingLoopback: Boolean,
    isShredding: Boolean,
    onEditMyNickname: () -> Unit,
    onOpenIdentityQr: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenServiceNotificationSettings: () -> Unit,
    onTransferToNewDevice: () -> Unit,
    onReceiveFromOldDevice: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenQrDownload: () -> Unit,
    onStartLoopback: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenShred: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shortIdentity = inboxId?.let {
        if (it.length > 14) "${it.take(6)}…${it.takeLast(6)}" else it
    } ?: walletAddress?.let {
        if (it.length > 12) "${it.take(6)}…${it.takeLast(4)}" else it
    } ?: "—"

    val updateSubtitle = when (updateStatus) {
        is UpdateStatus.Checking -> "Έλεγχος για ενημερώσεις…"
        is UpdateStatus.UpdateAvailable -> "Διαθέσιμη v${updateStatus.updateInfo.latestVersion} (Εγκατεστημένη v$currentVersion)"
        is UpdateStatus.Downloading -> "Λήψη ενημέρωσης (${updateStatus.progressPercent}%)…"
        is UpdateStatus.ReadyToInstall -> "Έτοιμη για εγκατάσταση (v${updateStatus.updateInfo.latestVersion})"
        else -> "Έκδοση v$currentVersion"
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item(key = "section_profile") {
                SettingsSectionHeader("Προφίλ")
                SettingsRow(
                    title = "Το όνομά μου",
                    subtitle = myNickname?.takeIf { it.isNotBlank() } ?: "Χωρίς όνομα • Μοιράζεται κρυπτογραφημένα",
                    onClick = onEditMyNickname
                )
                SettingsRow(
                    title = "Η ταυτότητά μου & QR",
                    subtitle = "Inbox ID: $shortIdentity",
                    onClick = onOpenIdentityQr
                )
                SettingsDivider()
            }

            item(key = "section_app") {
                SettingsSectionHeader("Εφαρμογή")
                SettingsRow(
                    title = "Ειδοποιήσεις μηνυμάτων",
                    subtitle = if (notificationsEnabled) "Ενεργές" else "Απενεργοποιημένες στις ρυθμίσεις συστήματος",
                    onClick = onOpenNotificationSettings
                )
                SettingsRow(
                    title = "Ειδοποίηση παρασκηνίου",
                    subtitle = if (isServiceNotificationVisible) {
                        "Ορατή • Άμεση λήψη εκτός εφαρμογής"
                    } else {
                        "Κρυφή • Ενεργή λήψη στο παρασκήνιο"
                    },
                    onClick = onOpenServiceNotificationSettings
                )
                SettingsRow(
                    title = "Μεταφορά σε νέα συσκευή",
                    subtitle = "Εξαγωγή ταυτότητας & ιστορικού μέσω QR Wi-Fi",
                    enabled = !isShredding,
                    onClick = onTransferToNewDevice
                )
                SettingsRow(
                    title = "Εισαγωγή από παλιά συσκευή",
                    subtitle = "Σάρωση ή λήψη αντιγράφου από άλλη συσκευή",
                    enabled = !isShredding,
                    onClick = onReceiveFromOldDevice
                )
                SettingsRow(
                    title = "Ενημέρωση εφαρμογής",
                    subtitle = updateSubtitle,
                    enabled = updateStatus !is UpdateStatus.Checking,
                    onClick = onCheckForUpdates
                )
                SettingsRow(
                    title = "Κοινοποίηση εφαρμογής (QR)",
                    subtitle = "Άμεση λήψη του APK από άλλη συσκευή",
                    onClick = onOpenQrDownload
                )
                SettingsDivider()
            }

            item(key = "section_security") {
                SettingsSectionHeader("Ασφάλεια & Έρευνα")
                SettingsRow(
                    title = "Κρυπτογράφηση & Προστασία",
                    subtitle = "XMTP v4 MLS • SQLCipher 4.6.1 • Keystore • Προστασία οθόνης",
                    showChevron = false,
                    onClick = null
                )
                SettingsRow(
                    title = "Δοκιμή Loopback MLS",
                    subtitle = if (isCreatingLoopback) {
                        "Δημιουργία τοπικής συνεδρίας…"
                    } else {
                        "Τοπική συνεδρία δοκιμής κρυπτογράφησης στη συσκευή"
                    },
                    enabled = !isCreatingLoopback && !isShredding,
                    onClick = onStartLoopback
                )
                SettingsRow(
                    title = "Καταγραφές & Έλεγχος (Logs & Audit)",
                    subtitle = "$logsCount καταγραφές • Διαγνωστικά & Crypto Audit",
                    onClick = onOpenLogs
                )
                SettingsDivider()
            }

            item(key = "section_danger") {
                SettingsSectionHeader(
                    title = "Διαγραφή δεδομένων",
                    color = MaterialTheme.colorScheme.error
                )
                SettingsRow(
                    title = "Οριστική διαγραφή (GDPR Art. 17)",
                    subtitle = "Μόνιμη καταστροφή κλειδιών & τοπικής βάσης δεδομένων",
                    titleColor = MaterialTheme.colorScheme.error,
                    enabled = !isShredding,
                    showChevron = false,
                    onClick = onOpenShred
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: (() -> Unit)?
) {
    val effectiveAlpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor.copy(alpha = effectiveAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = effectiveAlpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showChevron && onClick != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = effectiveAlpha)
            )
        }
    }
}

