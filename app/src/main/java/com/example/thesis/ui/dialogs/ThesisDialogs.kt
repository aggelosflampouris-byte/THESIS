package com.example.thesis.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.security.ScreenSecurityManager
import com.example.thesis.ui.util.readClipboardText
import com.example.thesis.updater.AppUpdateInfo
import com.example.thesis.updater.UpdateStatus
import com.example.thesis.util.CryptoUtils
import org.xmtp.android.library.Conversation
import java.io.File
import java.util.Locale

@Composable
internal fun NewConversationDialog(
    isScreenProtectionEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onStartConversation: (String, String?) -> Unit
) {
    var peerInput by rememberSaveable { mutableStateOf("") }
    var nicknameInput by rememberSaveable { mutableStateOf("") }
    val isValidInput = remember(peerInput) {
        val trimmed = peerInput.trim()
        CryptoUtils.isValidEthereumAddress(trimmed) || CryptoUtils.isValidInboxId(trimmed)
    }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(isScreenProtectionEnabled),
        title = { Text("Νέα Συνομιλία MLS") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Εισάγετε ταυτότητα παραλήπτη:")

                OutlinedTextField(
                    value = peerInput,
                    onValueChange = { peerInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Διεύθυνση (0x...) ή XMTP Inbox ID") },
                    placeholder = { Text("0x… ή 64-hex inbox ID") },
                    singleLine = true,
                    isError = peerInput.isNotBlank() && !isValidInput,
                    supportingText = {
                        if (peerInput.isNotBlank() && !isValidInput) {
                            Text("Απαιτείται διεύθυνση Ethereum (0x...) ή XMTP Inbox ID (64 hex).")
                        } else {
                            Text("Υποστηρίζονται διευθύνσεις Ethereum (0x...) και XMTP Inbox IDs.", fontSize = 10.sp)
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    )
                )

                // Sacamos el boton de pegado fuera del campo para aprovechar todo el ancho en direcciones hexadecimales largas.
                OutlinedButton(
                    onClick = { readClipboardText(context)?.let { peerInput = it.trim() } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Επικόλληση από πρόχειρο", fontSize = 12.sp)
                }

                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it.take(NicknamePolicy.MAX_NICKNAME_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ψευδώνυμο επαφής (προαιρετικό)") },
                    placeholder = { Text("π.χ. Μαρία") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onStartConversation(
                        peerInput.trim(),
                        NicknamePolicy.sanitize(nicknameInput)
                    )
                },
                enabled = isValidInput
            ) {
                Text("Σύνδεση")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Ακύρωση") }
        }
    )
}

@Composable
internal fun IdentityDetailsDialog(
    address: String?,
    inboxId: String?,
    myNickname: String? = null,
    currentVersion: String,
    isCheckingUpdate: Boolean = false,
    notificationsEnabled: Boolean = true,
    isServiceNotificationVisible: Boolean = true,
    isScreenProtectionEnabled: Boolean = true,
    onToggleScreenProtection: (Boolean) -> Unit = {},
    onSaveMyNickname: (String) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenServiceNotificationSettings: () -> Unit = {},
    onCopyAddress: () -> Unit,
    onCopyInboxId: () -> Unit,
    onDismiss: () -> Unit
) {
    var nicknameDraft by rememberSaveable(myNickname) { mutableStateOf(myNickname.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(isScreenProtectionEnabled),
        title = { Text("Ταυτότητα και Ασφάλεια") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Κατάσταση: Δοκιμαστική ταυτότητα SECP256K1")
                Text("Δίκτυο: XMTP DEV Network (MLS Protocol)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                address?.let {
                    Text("Διεύθυνση Πορτοφολιού (Ethereum)", style = MaterialTheme.typography.labelMedium)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onCopyAddress, modifier = Modifier.fillMaxWidth()) {
                        Text("Αντιγραφή Διεύθυνσης")
                    }
                }

                inboxId?.let {
                    Text("XMTP Inbox ID (MLS Ledger)", style = MaterialTheme.typography.labelMedium)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onCopyInboxId, modifier = Modifier.fillMaxWidth()) {
                        Text("Αντιγραφή Inbox ID")
                    }
                }

                Text(
                    "Για επικοινωνία μεταξύ συσκευών, αντιγράψτε το Inbox ID ή τη Διεύθυνση και εισάγετέ τη στη δεύτερη συσκευή στο '+ Νέα'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    "Η ταυτότητα προστατεύεται από Hardware-backed Master Key (KEK) στο Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Text("Το ψευδώνυμό σας (μοιράζεται κρυπτογραφημένα)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = nicknameDraft,
                    onValueChange = { nicknameDraft = it.take(NicknamePolicy.MAX_NICKNAME_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Εισάγετε ψευδώνυμο...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    )
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSaveMyNickname(nicknameDraft) }
                    ) {
                        Text("Αποθήκευση", fontSize = 12.sp)
                    }
                    if (!myNickname.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                nicknameDraft = ""
                                onSaveMyNickname("")
                            }
                        ) {
                            Text("Εκκαθάριση", fontSize = 12.sp)
                        }
                    }
                }
                HorizontalDivider()
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Έκδοση: v$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    TextButton(onClick = onCheckForUpdates, enabled = !isCheckingUpdate) {
                        Text(if (isCheckingUpdate) "Έλεγχος..." else "Έλεγχος Ενημερώσεων", fontSize = 12.sp)
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (notificationsEnabled) "Ειδοποιήσεις: Ενεργές" else "Ειδοποιήσεις: Ανενεργές",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notificationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    TextButton(onClick = onOpenNotificationSettings) {
                        Text("Ρυθμίσεις", fontSize = 12.sp)
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (isServiceNotificationVisible) {
                            "Ειδοποίηση παρασκηνίου: Ορατή"
                        } else {
                            "Ειδοποίηση παρασκηνίου: Κρυφή (ενεργή λήψη)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    TextButton(onClick = onOpenServiceNotificationSettings) {
                        Text(if (isServiceNotificationVisible) "Απόκρυψη" else "Κανάλι", fontSize = 12.sp)
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Προστασία Οθόνης (Screenshot / Εγγραφή)",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "Αποκλείει στιγμιότυπα, εγγραφή οθόνης και προεπισκόπηση στα Πρόσφατα (FLAG_SECURE).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = isScreenProtectionEnabled,
                        onCheckedChange = onToggleScreenProtection
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Κλείσιμο") }
        }
    )
}

@Composable
internal fun GDPRShredConfirmationDialog(
    isScreenProtectionEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onConfirmShred: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(isScreenProtectionEnabled),
        title = { Text("Οριστική Διαγραφή (GDPR Art. 17)") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Αυτή η ενέργεια θα καταστρέψει μόνιμα το κλειδί στο Hardware Keystore και θα διαγράψει τη βάση δεδομένων SQLCipher.\n\nΔεν υπάρχει δυνατότητα ανάκτησης."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmShred) {
                Text("Επιβεβαίωση Διαγραφής", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Ακύρωση") }
        }
    )
}

@Composable
internal fun MLSProtocolInspectorDialog(
    conversation: Conversation,
    localInboxId: String,
    messageCount: Int,
    contactNickname: String? = null,
    isScreenProtectionEnabled: Boolean = true,
    onEditContactNickname: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onCopyTopic: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(isScreenProtectionEnabled),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "MLS Protocol Inspector",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InspectorParamRow(label = "Protocol Standard", value = "RFC 9420 (TreeKEM MLS)")
                InspectorParamRow(label = "Cipher Suite", value = "DHKEM-X25519-AES128GCM-SHA256")
                InspectorParamRow(
                    label = "Session Topology",
                    value = when (conversation.type) {
                        Conversation.Type.GROUP -> "MLS Group Ratchet (Loopback/Multi)"
                        Conversation.Type.DM -> "MLS 1-on-1 Direct Message"
                    }
                )
                InspectorParamRow(
                    label = "Local Client Inbox",
                    value = if (localInboxId.length >= 12) "${localInboxId.take(6)}...${localInboxId.takeLast(6)}" else localInboxId
                )
                if (onEditContactNickname != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ψευδώνυμο Επαφής",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                            Text(
                                text = contactNickname ?: "Χωρίς ψευδώνυμο",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                        TextButton(onClick = onEditContactNickname) {
                            Text("Αλλαγή", fontSize = 12.sp)
                        }
                    }
                }
                InspectorParamRow(
                    label = "Commit Counter",
                    value = "$messageCount message commit(s)"
                )
                InspectorParamRow(
                    label = "Forward Secrecy",
                    value = "Continuous asymmetric ratchet update"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Column {
                    Text(
                        text = "MLS Topic Hash",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = conversation.topic,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onCopyTopic) {
                Text("Αντιγραφή Topic", fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Κλείσιμο", fontSize = 12.sp)
            }
        }
    )
}

@Composable
internal fun EditContactNicknameDialog(
    targetLabel: String,
    currentLocalNickname: String?,
    sharedPeerNickname: String?,
    isScreenProtectionEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by rememberSaveable(currentLocalNickname, sharedPeerNickname) {
        mutableStateOf(currentLocalNickname.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(isScreenProtectionEnabled),
        title = { Text("Ψευδώνυμο Επαφής") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Ταυτότητα: $targetLabel",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!sharedPeerNickname.isNullOrBlank()) {
                    Text(
                        text = "Προτεινόμενο από τον συνομιλητή: $sharedPeerNickname",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(NicknamePolicy.MAX_NICKNAME_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Τοπικό ψευδώνυμο") },
                    placeholder = { Text(sharedPeerNickname ?: "π.χ. Κώστας") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(input) }) {
                Text("Αποθήκευση")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!currentLocalNickname.isNullOrBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text("Επαναφορά")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Ακύρωση")
                }
            }
        }
    )
}

@Composable
private fun InspectorParamRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
    }
}

@Composable
internal fun AppUpdateDialog(
    updateStatus: UpdateStatus,
    canRequestInstall: Boolean,
    isScreenProtectionEnabled: Boolean = true,
    onDownload: (AppUpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val dialogProperties = ScreenSecurityManager.dialogProperties(isScreenProtectionEnabled)
    when (updateStatus) {
        is UpdateStatus.UpdateAvailable -> {
            val info = updateStatus.updateInfo
            AlertDialog(
                onDismissRequest = onDismiss,
                properties = dialogProperties,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = {
                    Text(
                        text = "Νέα Έκδοση: v${info.latestVersion}",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "Τρέχουσα: v${info.currentVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(14.dp)
                                    .align(Alignment.CenterVertically)
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "Νέα: v${info.latestVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (info.releaseTitle.isNotBlank()) {
                            Text(
                                text = info.releaseTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (info.assetSizeBytes > 0L) {
                            val sizeMb = info.assetSizeBytes / (1024f * 1024f)
                            Text(
                                text = "Μέγεθος: ${String.format(Locale.US, "%.1f", sizeMb)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = "Σημειώσεις Έκδοσης:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = info.releaseNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { onDownload(info) }) {
                        Text("Λήψη & Ενημέρωση")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Αργότερα")
                    }
                }
            )
        }

        is UpdateStatus.Downloading -> {
            val info = updateStatus.updateInfo
            AlertDialog(
                onDismissRequest = { /* Modal during active download */ },
                properties = ScreenSecurityManager.dialogProperties(
                    enabled = isScreenProtectionEnabled,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                ),
                title = { Text("Λήψη Ενημέρωσης v${info.latestVersion}") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { updateStatus.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${updateStatus.progressPercent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (updateStatus.totalBytes > 0) {
                                val downloadedMb = updateStatus.bytesDownloaded / (1024f * 1024f)
                                val totalMb = updateStatus.totalBytes / (1024f * 1024f)
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", downloadedMb)} / ${String.format(Locale.US, "%.1f", totalMb)} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = "Παρακαλώ περιμένετε καθώς ολοκληρώνεται η ασφαλής λήψη του νέου πακέτου APK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateStatus.ReadyToInstall -> {
            val info = updateStatus.updateInfo
            AlertDialog(
                onDismissRequest = onDismiss,
                properties = dialogProperties,
                icon = {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = { Text("Η Λήψη Ολοκληρώθηκε!") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Το πακέτο v${info.latestVersion} είναι έτοιμο για εγκατάσταση.")
                        if (!canRequestInstall) {
                            Text(
                                text = "Απαιτείται άδεια εγκατάστασης από άγνωστες πηγές.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    if (!canRequestInstall) {
                        Button(onClick = onOpenSettings) {
                            Text("Ενεργοποίηση Άδειας")
                        }
                    } else {
                        Button(onClick = { onInstall(updateStatus.apkFile) }) {
                            Text("Εγκατάσταση Τώρα")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Κλείσιμο")
                    }
                }
            )
        }

        is UpdateStatus.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                properties = dialogProperties,
                title = { Text("Σφάλμα Ενημέρωσης") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(updateStatus.message)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Εντάξει")
                    }
                }
            )
        }

        else -> {}
    }
}
