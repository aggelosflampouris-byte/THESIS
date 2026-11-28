package com.example.thesis.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.R
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.repository.GroupDetailsSnapshot
import com.example.thesis.repository.GroupManagementPolicy
import com.example.thesis.repository.GroupMemberInfo
import com.example.thesis.security.ScreenSecurityManager
import com.example.thesis.ui.components.QrCodeView
import com.example.thesis.ui.components.QrScannerView
import com.example.thesis.ui.util.ChatFormatting
import com.example.thesis.ui.util.readClipboardText
import com.example.thesis.updater.AppUpdateInfo
import com.example.thesis.updater.UpdateStatus
import com.example.thesis.xmtp.cachedGroupName
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.libxmtp.PermissionLevel
import java.io.File
import java.util.Locale

internal data class KnownContactOption(
    val inboxId: String,
    val displayName: String
)

// Campo compartido con validacion, escaner QR y pegado rapido para Inbox ID o direccion Ethereum.
@Composable
private fun PeerIdentifierInputSection(
    value: String,
    onValueChange: (String) -> Unit,
    showQrScanner: Boolean,
    onShowQrScannerChange: (Boolean) -> Unit,
    canUseCamera: Boolean,
    onRequestCameraPermission: () -> Unit,
    scanPrompt: String,
    fieldLabel: String
) {
    val context = LocalContext.current
    val isValid = remember(value) {
        value.isBlank() || GroupManagementPolicy.parsePeerIdentifier(value) != null
    }
    if (showQrScanner && canUseCamera) {
        Text(
            text = scanPrompt,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QrScannerView(
            onPairingUriDetected = { scanned ->
                onValueChange(scanned.trim())
                onShowQrScannerChange(false)
            },
            validator = { raw -> GroupManagementPolicy.parsePeerIdentifier(raw) != null },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        OutlinedButton(
            onClick = { onShowQrScannerChange(false) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Εισαγωγή χειροκίνητα")
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(fieldLabel) },
            placeholder = { Text("0x… ή 64-hex Inbox ID") },
            singleLine = true,
            isError = value.isNotBlank() && !isValid,
            supportingText = {
                if (value.isNotBlank() && !isValid) {
                    Text("Απαιτείται διεύθυνση 0x… ή 64-hex Inbox ID.")
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (canUseCamera) {
                        onShowQrScannerChange(true)
                    } else {
                        onRequestCameraPermission()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Σάρωση QR", maxLines = 1)
            }
            OutlinedButton(
                onClick = { readClipboardText(context)?.let { onValueChange(it.trim()) } },
                modifier = Modifier.weight(1f)
            ) {
                Text("Επικόλληση", maxLines = 1)
            }
        }
    }
}

@Composable
internal fun NewConversationDialog(
    knownContacts: List<KnownContactOption> = emptyList(),
    canUseCamera: Boolean = false,
    onRequestCameraPermission: () -> Unit = {},
    onDismiss: () -> Unit,
    onStartConversation: (String, String?) -> Unit,
    onStartGroup: (String, List<String>) -> Unit = { _, _ -> }
) {
    var isGroupMode by rememberSaveable { mutableStateOf(false) }
    var peerInput by rememberSaveable { mutableStateOf("") }
    var nicknameInput by rememberSaveable { mutableStateOf("") }
    var groupNameInput by rememberSaveable { mutableStateOf("") }
    var selectedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var customGroupMembers by remember { mutableStateOf<List<String>>(emptyList()) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }

    val isDmValid = remember(peerInput) {
        GroupManagementPolicy.parsePeerIdentifier(peerInput) != null
    }
    val canAddCustomMember = remember(peerInput) {
        GroupManagementPolicy.parsePeerIdentifier(peerInput) != null
    }
    val combinedGroupMembers = remember(selectedContactIds, customGroupMembers) {
        (selectedContactIds.toList() + customGroupMembers).distinctBy { it.lowercase(Locale.ROOT) }
    }
    val isGroupValid = remember(groupNameInput, combinedGroupMembers) {
        val cleanName = GroupManagementPolicy.sanitizeGroupName(groupNameInput)
        cleanName != null &&
            !GroupManagementPolicy.isLoopbackGroupName(cleanName) &&
            combinedGroupMembers.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
        title = { Text(if (isGroupMode) "Νέα Ομάδα MLS" else "Νέα Συνομιλία") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isGroupMode,
                        onClick = {
                            isGroupMode = false
                            showQrScanner = false
                        },
                        label = { Text("Συνομιλία") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isGroupMode,
                        onClick = {
                            isGroupMode = true
                            showQrScanner = false
                        },
                        label = { Text("Ομάδα") },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!isGroupMode) {
                    if (!showQrScanner) {
                        Text(
                            text = "Εισάγετε διεύθυνση, Inbox ID ή σαρώστε QR επαφής:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PeerIdentifierInputSection(
                        value = peerInput,
                        onValueChange = { peerInput = it },
                        showQrScanner = showQrScanner,
                        onShowQrScannerChange = { showQrScanner = it },
                        canUseCamera = canUseCamera,
                        onRequestCameraPermission = onRequestCameraPermission,
                        scanPrompt = "Σαρώστε το QR ταυτότητας της επαφής σας.",
                        fieldLabel = "Διεύθυνση (0x...) ή Inbox ID"
                    )
                    if (!showQrScanner) {
                        OutlinedTextField(
                            value = nicknameInput,
                            onValueChange = { nicknameInput = it.take(NicknamePolicy.MAX_NICKNAME_LENGTH) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Όνομα επαφής (προαιρετικό)") },
                            placeholder = { Text("π.χ. Μαρία") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            )
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = groupNameInput,
                        onValueChange = { groupNameInput = it.take(GroupManagementPolicy.MAX_GROUP_NAME_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Όνομα ομάδας") },
                        placeholder = { Text("π.χ. Ομάδα Εργασίας") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        )
                    )

                    if (knownContacts.isNotEmpty()) {
                        Text(
                            text = "Επιλογή από επαφές:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            knownContacts.forEach { contact ->
                                val normalized = contact.inboxId.lowercase(Locale.ROOT)
                                val selected = normalized in selectedContactIds
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedContactIds = if (selected) {
                                            selectedContactIds - normalized
                                        } else {
                                            selectedContactIds + normalized
                                        }
                                    },
                                    label = { Text(contact.displayName, maxLines = 1) }
                                )
                            }
                        }
                    }

                    Text(
                        text = "Προσθήκη μέλους (${combinedGroupMembers.size} επιλεγμένα):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PeerIdentifierInputSection(
                        value = peerInput,
                        onValueChange = { peerInput = it },
                        showQrScanner = showQrScanner,
                        onShowQrScannerChange = { showQrScanner = it },
                        canUseCamera = canUseCamera,
                        onRequestCameraPermission = onRequestCameraPermission,
                        scanPrompt = "Σαρώστε το QR του μέλους που θέλετε να προσθέσετε.",
                        fieldLabel = "Διεύθυνση (0x...) ή Inbox ID μέλους"
                    )
                    if (!showQrScanner) {
                        OutlinedButton(
                            onClick = {
                                val candidate = peerInput.trim()
                                if (candidate.isNotEmpty() &&
                                    customGroupMembers.none { it.equals(candidate, ignoreCase = true) }
                                ) {
                                    customGroupMembers = customGroupMembers + candidate
                                }
                                peerInput = ""
                            },
                            enabled = canAddCustomMember,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Προσθήκη στη λίστα")
                        }
                    }

                    if (customGroupMembers.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            customGroupMembers.forEach { rawId ->
                                val masked = if (rawId.length >= 14) {
                                    "${rawId.take(6)}…${rawId.takeLast(4)}"
                                } else {
                                    rawId
                                }
                                FilterChip(
                                    selected = true,
                                    onClick = { customGroupMembers = customGroupMembers - rawId },
                                    label = { Text(masked) },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Αφαίρεση",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isGroupMode) {
                        onStartGroup(groupNameInput.trim(), combinedGroupMembers)
                    } else {
                        onStartConversation(
                            peerInput.trim(),
                            NicknamePolicy.sanitize(nicknameInput)
                        )
                    }
                },
                enabled = if (isGroupMode) isGroupValid else isDmValid
            ) {
                Text(if (isGroupMode) "Δημιουργία" else "Σύνδεση")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Ακύρωση") }
        }
    )
}

// Διαλογο de QR de contacto limpio para compartir Inbox ID o direccion sin saturar la pantalla.
@Composable
internal fun MyIdentityQrDialog(
    address: String?,
    inboxId: String?,
    onCopyAddress: () -> Unit,
    onCopyInboxId: () -> Unit,
    onDismiss: () -> Unit
) {
    val qrPayload = inboxId ?: address.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
        title = { Text("Η ταυτότητά μου") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (qrPayload.isNotBlank()) {
                    QrCodeView(
                        content = qrPayload,
                        modifier = Modifier.size(200.dp),
                        quietZone = 12.dp
                    )
                    Text(
                        text = "Σαρώστε το QR από άλλη συσκευή για άμεση έναρξη συνομιλίας.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                inboxId?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("XMTP Inbox ID", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            IconButton(onClick = onCopyInboxId) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_content_copy),
                                    contentDescription = "Αντιγραφή Inbox ID",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                address?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Διεύθυνση (Ethereum)", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            IconButton(onClick = onCopyAddress) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_content_copy),
                                    contentDescription = "Αντιγραφή διεύθυνσης",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Κλείσιμο") }
        }
    )
}

@Composable
internal fun EditMyNicknameDialog(
    currentNickname: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by rememberSaveable(currentNickname) { mutableStateOf(currentNickname.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
        title = { Text("Το όνομά μου") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Μοιράζεται κρυπτογραφημένα με τις επαφές σας.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(NicknamePolicy.MAX_NICKNAME_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Εισάγετε όνομα…") },
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
                if (!currentNickname.isNullOrBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text("Εκκαθάριση")
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
internal fun GDPRShredConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirmShred: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
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
    memberCount: Int? = null,
    onEditContactNickname: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onCopyTopic: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
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
                    value = when (conversation) {
                        is Conversation.Dm -> "MLS 1-on-1 Direct Message"
                        is Conversation.Group -> if (GroupManagementPolicy.isLoopbackGroupName(conversation.group.cachedGroupName())) {
                            "MLS Loopback Group (1-Device)"
                        } else {
                            "MLS Group Ratchet (TreeKEM)"
                        }
                    }
                )
                if (conversation is Conversation.Group && memberCount != null && memberCount > 0) {
                    InspectorParamRow(
                        label = "Group Members",
                        value = GroupManagementPolicy.formatGroupMemberCountSubtitle(memberCount)
                    )
                }
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
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by rememberSaveable(currentLocalNickname, sharedPeerNickname) {
        mutableStateOf(currentLocalNickname.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
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

// Dialogo minimalista para gestionar nombre, miembros, incorporaciones y salida de un grupo MLS.
@Composable
internal fun GroupInfoDialog(
    groupDetails: GroupDetailsSnapshot?,
    fallbackTitle: String,
    nicknameSnapshot: NicknameSnapshot,
    knownContacts: List<KnownContactOption>,
    isUpdating: Boolean,
    canUseCamera: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRenameGroup: (String) -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onLeaveGroup: () -> Unit,
    onOpenProtocolInspector: () -> Unit,
    onDismiss: () -> Unit
) {
    val activeTitle = groupDetails?.name?.ifBlank { fallbackTitle } ?: fallbackTitle
    var isEditingName by rememberSaveable { mutableStateOf(false) }
    var renameInput by rememberSaveable(activeTitle) { mutableStateOf(activeTitle) }
    var showAddMember by rememberSaveable { mutableStateOf(false) }
    var memberInput by rememberSaveable { mutableStateOf("") }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    val canManage = groupDetails != null && GroupManagementPolicy.canManageMembers(groupDetails)
    val members = groupDetails?.members.orEmpty()
    val availableContacts = remember(knownContacts, members) {
        val currentIds = members.map { it.inboxId.lowercase(Locale.ROOT) }.toSet()
        knownContacts.filter { it.inboxId.lowercase(Locale.ROOT) !in currentIds }
    }
    val canSubmitMember = remember(memberInput) {
        GroupManagementPolicy.parsePeerIdentifier(memberInput) != null
    }
    val canSubmitRename = remember(renameInput, activeTitle) {
        val clean = GroupManagementPolicy.sanitizeGroupName(renameInput)
        clean != null && !GroupManagementPolicy.isLoopbackGroupName(clean) && clean != activeTitle
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = ScreenSecurityManager.dialogProperties(),
        title = { Text("Πληροφορίες ομάδας") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (groupDetails == null || isUpdating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (isEditingName && canManage) {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it.take(GroupManagementPolicy.MAX_GROUP_NAME_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Όνομα ομάδας") },
                        singleLine = true,
                        enabled = !isUpdating
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                renameInput = activeTitle
                                isEditingName = false
                            }
                        ) {
                            Text("Ακύρωση")
                        }
                        TextButton(
                            onClick = {
                                onRenameGroup(renameInput)
                                isEditingName = false
                            },
                            enabled = canSubmitRename && !isUpdating
                        ) {
                            Text("Αποθήκευση")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = GroupManagementPolicy.formatGroupMemberCountSubtitle(members.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (canManage) {
                            IconButton(
                                onClick = {
                                    renameInput = activeTitle
                                    isEditingName = true
                                },
                                enabled = !isUpdating
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Μετονομασία ομάδας",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Μέλη (${members.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                members.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        nicknameSnapshot = nicknameSnapshot,
                        canRemove = groupDetails != null &&
                            !isUpdating &&
                            GroupManagementPolicy.canRemoveMember(groupDetails, member),
                        onRemove = { onRemoveMember(member.inboxId) }
                    )
                }

                if (canManage) {
                    if (!showAddMember) {
                        OutlinedButton(
                            onClick = { showAddMember = true },
                            enabled = !isUpdating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Προσθήκη μέλους")
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Προσθήκη μέλους",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    TextButton(
                                        onClick = {
                                            showAddMember = false
                                            showQrScanner = false
                                            memberInput = ""
                                        }
                                    ) {
                                        Text("Κλείσιμο")
                                    }
                                }

                                if (availableContacts.isNotEmpty() && !showQrScanner) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        availableContacts.forEach { contact ->
                                            FilterChip(
                                                selected = false,
                                                enabled = !isUpdating,
                                                onClick = {
                                                    onAddMember(contact.inboxId)
                                                    showAddMember = false
                                                },
                                                label = { Text("+ ${contact.displayName}") }
                                            )
                                        }
                                    }
                                }

                                PeerIdentifierInputSection(
                                    value = memberInput,
                                    onValueChange = { memberInput = it },
                                    showQrScanner = showQrScanner,
                                    onShowQrScannerChange = { showQrScanner = it },
                                    canUseCamera = canUseCamera,
                                    onRequestCameraPermission = onRequestCameraPermission,
                                    scanPrompt = "Σαρώστε το QR του νέου μέλους.",
                                    fieldLabel = "Διεύθυνση (0x...) ή Inbox ID"
                                )
                                if (!showQrScanner) {
                                    Button(
                                        onClick = {
                                            val candidate = memberInput.trim()
                                            memberInput = ""
                                            showAddMember = false
                                            onAddMember(candidate)
                                        },
                                        enabled = canSubmitMember && !isUpdating,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Προσθήκη")
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                TextButton(
                    onClick = onOpenProtocolInspector,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Πληροφορίες & Πρωτόκολλο MLS")
                }

                if (groupDetails?.isActive != false) {
                    if (!confirmLeave) {
                        TextButton(
                            onClick = { confirmLeave = true },
                            enabled = !isUpdating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Αποχώρηση από την ομάδα",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { confirmLeave = false },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Ακύρωση")
                            }
                            Button(
                                onClick = {
                                    confirmLeave = false
                                    onLeaveGroup()
                                },
                                enabled = !isUpdating,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Επιβεβαίωση")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Κλείσιμο") }
        }
    )
}

@Composable
private fun GroupMemberRow(
    member: GroupMemberInfo,
    nicknameSnapshot: NicknameSnapshot,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    val label = remember(member.inboxId, nicknameSnapshot) {
        GroupManagementPolicy.resolveSenderDisplayLabel(member.inboxId, nicknameSnapshot)
    }
    val displayLabel = if (member.isLocalUser) "$label (Εσείς)" else label
    val initials = remember(label) { ChatFormatting.extractAvatarInitials(label) }
    val roleLabel = when (member.permissionLevel) {
        PermissionLevel.SUPER_ADMIN -> "Κύριος διαχειριστής"
        PermissionLevel.ADMIN -> "Διαχειριστής"
        PermissionLevel.MEMBER -> "Μέλος"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = roleLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canRemove) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Αφαίρεση μέλους",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
    onDownload: (AppUpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val dialogProperties = ScreenSecurityManager.dialogProperties()
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
