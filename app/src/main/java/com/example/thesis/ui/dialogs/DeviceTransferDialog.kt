package com.example.thesis.ui.dialogs

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.thesis.security.ScreenSecurityManager
import com.example.thesis.ui.DeviceTransferState
import com.example.thesis.ui.components.QrCodeView
import com.example.thesis.ui.components.QrScannerView

@Composable
internal fun DeviceTransferDialog(
    state: DeviceTransferState,
    onPairingUriSubmitted: (String) -> Unit,
    onToggleRevokeOldDevice: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onWipeOldDeviceAfterTransfer: () -> Unit
) {
    if (state is DeviceTransferState.Idle) return

    val canDismiss = state !is DeviceTransferState.ApplyingImport
    val titleText = when (state) {
        is DeviceTransferState.AwaitingScan,
        is DeviceTransferState.Sending,
        is DeviceTransferState.SendCompleted -> "Μεταφορά σε Νέα Συσκευή"
        is DeviceTransferState.WaitingForSender,
        is DeviceTransferState.Receiving,
        is DeviceTransferState.ApplyingImport,
        is DeviceTransferState.ReceiveCompleted -> "Λήψη από Παλιά Συσκευή"
        is DeviceTransferState.Error -> if (state.isSender) "Σφάλμα Αποστολής" else "Σφάλμα Λήψης"
        DeviceTransferState.Idle -> ""
    }

    AlertDialog(
        onDismissRequest = { if (canDismiss) onCancel() },
        properties = ScreenSecurityManager.dialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = false
        ),
        title = { Text(titleText) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (state) {
                    is DeviceTransferState.AwaitingScan -> SenderScanStep(
                        walletAddress = state.walletAddress,
                        onPairingUriSubmitted = onPairingUriSubmitted
                    )
                    is DeviceTransferState.WaitingForSender -> ReceiverWaitStep(
                        state = state,
                        onToggleRevokeOldDevice = onToggleRevokeOldDevice
                    )
                    is DeviceTransferState.Sending -> TransferProgressStep(
                        progress = state.progress,
                        statusMessage = state.statusMessage,
                        subtitle = "Κρατήστε και τις δύο συσκευές ενεργές στο ίδιο δίκτυο Wi-Fi / Hotspot."
                    )
                    is DeviceTransferState.Receiving -> TransferProgressStep(
                        progress = state.progress,
                        statusMessage = state.statusMessage,
                        subtitle = "Κρυπτογραφημένη μεταφορά σε εξέλιξη..."
                    )
                    is DeviceTransferState.ApplyingImport -> TransferProgressStep(
                        progress = null,
                        statusMessage = state.statusMessage,
                        subtitle = "Παρακαλώ μην κλείνετε την εφαρμογή."
                    )
                    is DeviceTransferState.SendCompleted -> SendCompletedStep(state = state)
                    is DeviceTransferState.ReceiveCompleted -> ReceiveCompletedStep(state = state)
                    is DeviceTransferState.Error -> TransferErrorStep(message = state.message)
                    DeviceTransferState.Idle -> {}
                }
            }
        },
        confirmButton = {
            when (state) {
                is DeviceTransferState.SendCompleted -> {
                    Button(
                        onClick = onWipeOldDeviceAfterTransfer,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Διαγραφή Παλιάς Συσκευής (GDPR)")
                    }
                }
                is DeviceTransferState.ReceiveCompleted -> {
                    Button(onClick = onCancel) {
                        Text("Συνέχεια στις Συνομιλίες")
                    }
                }
                is DeviceTransferState.Error -> {
                    TextButton(onClick = onCancel) { Text("Κλείσιμο") }
                }
                else -> {}
            }
        },
        dismissButton = {
            when (state) {
                is DeviceTransferState.SendCompleted -> {
                    TextButton(onClick = onCancel) { Text("Κλείσιμο") }
                }
                is DeviceTransferState.AwaitingScan,
                is DeviceTransferState.WaitingForSender,
                is DeviceTransferState.Sending,
                is DeviceTransferState.Receiving -> {
                    TextButton(onClick = onCancel) { Text("Ακύρωση") }
                }
                else -> {}
            }
        }
    )
}

@Composable
private fun SenderScanStep(
    walletAddress: String,
    onPairingUriSubmitted: (String) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var useManualEntry by rememberSaveable { mutableStateOf(false) }
    var manualUri by rememberSaveable { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Text(
        text = "Ανοίξτε «Μεταφορά από Παλιά Συσκευή (QR)» στη νέα συσκευή (στο ίδιο Wi-Fi ή Hotspot) και σαρώστε τον κωδικό QR της.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = "Λογαριασμός: ${walletAddress.take(10)}...${walletAddress.takeLast(4)}",
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.primary
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(onClick = { useManualEntry = false }) {
            Text("Κάμερα QR", fontSize = 12.sp)
        }
        OutlinedButton(onClick = { useManualEntry = true }) {
            Text("Χειροκίνητος Κωδικός", fontSize = 12.sp)
        }
    }

    if (!useManualEntry) {
        if (hasCameraPermission) {
            QrScannerView(
                onPairingUriDetected = onPairingUriSubmitted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Απαιτείται άδεια κάμερας για σάρωση του QR ζεύξης.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    ) {
                        Text("Παροχή Άδειας Κάμερας", fontSize = 12.sp)
                    }
                }
            }
        }
    } else {
        OutlinedTextField(
            value = manualUri,
            onValueChange = { manualUri = it },
            label = { Text("Κωδικός Ζεύξης (thesis-xfer://...)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
        Button(
            onClick = { onPairingUriSubmitted(manualUri) },
            enabled = manualUri.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Σύνδεση & Αποστολή")
        }
    }
}

@Composable
private fun ReceiverWaitStep(
    state: DeviceTransferState.WaitingForSender,
    onToggleRevokeOldDevice: (Boolean) -> Unit
) {
    var showRawUri by rememberSaveable { mutableStateOf(false) }

    if (state.hasExistingSessionToReplace) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Text(
                text = "Προσοχή: Η εισαγωγή θα διαγράψει οριστικά την υπάρχουσα τοπική συνεδρία αυτής της συσκευής.",
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    Text(
        text = "Σαρώστε αυτόν τον κωδικό QR από την παλιά συσκευή (Ρυθμίσεις Ταυτότητας → Μεταφορά σε Νέα Συσκευή).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QrCodeView(
            content = state.pairingUri,
            modifier = Modifier.size(200.dp),
            quietZone = 12.dp
        )
        Text(
            text = "Τοπικό δίκτυο: ${state.hostDisplay}:${state.port}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = state.revokeOldDevice,
            onCheckedChange = onToggleRevokeOldDevice
        )
        Text(
            text = "Ανάκληση παλιάς εγκατάστασης MLS μετά τη μεταφορά",
            style = MaterialTheme.typography.bodySmall
        )
    }

    TextButton(onClick = { showRawUri = !showRawUri }) {
        Text(if (showRawUri) "Απόκρυψη κωδικού ζεύξης" else "Εμφάνιση κωδικού ζεύξης (χειροκίνητα)", fontSize = 12.sp)
    }
    if (showRawUri) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ) {
            Text(
                text = state.pairingUri,
                modifier = Modifier.padding(8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun TransferProgressStep(
    progress: Float?,
    statusMessage: String,
    subtitle: String
) {
    Text(
        text = statusMessage,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(4.dp))
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SendCompletedStep(state: DeviceTransferState.SendCompleted) {
    Text(
        text = "Η μεταφορά ολοκληρώθηκε με επιτυχία!",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = "Ιστορικό MLS: ${if (state.importedHistory) "Μεταφέρθηκε" else "Μόνο ταυτότητα"} • Ανάκληση παλιάς συσκευής: ${if (state.revokedOldInstallations) "Επιτυχής" else "Εκκρεμεί"}",
        style = MaterialTheme.typography.bodySmall
    )
    if (!state.newInstallationId.isNullOrBlank()) {
        Text(
            text = "Νέο Installation ID: ${state.newInstallationId.take(14)}...",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Text(
        text = "Για μέγιστη ασφάλεια GDPR, διαγράψτε οριστικά τα τοπικά δεδομένα από αυτήν την παλιά συσκευή.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}

@Composable
private fun ReceiveCompletedStep(state: DeviceTransferState.ReceiveCompleted) {
    val masked = if (state.walletAddress.length >= 12) {
        "${state.walletAddress.take(8)}...${state.walletAddress.takeLast(4)}"
    } else {
        state.walletAddress
    }
    Text(
        text = "Η νέα συσκευή είναι έτοιμη!",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = "Ταυτότητα: $masked\nΙστορικό MLS: ${if (state.importedHistory) "Εισήχθη" else "Δεν βρέθηκε"}\nΑνάκληση παλιάς εγκατάστασης: ${if (state.revokedOldInstallations) "Ναι" else "Όχι"}",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun TransferErrorStep(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Start
        )
    }
}
