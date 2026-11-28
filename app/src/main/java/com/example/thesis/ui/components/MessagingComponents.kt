package com.example.thesis.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.thesis.R
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.ui.util.ChatFormatting
import com.example.thesis.xmtp.XmtpEngineState
import com.example.thesis.xmtp.cachedGroupName
import org.xmtp.android.library.Conversation
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessagingTopBar(
    selectedConversation: Conversation?,
    nicknameSnapshot: NicknameSnapshot = NicknameSnapshot(),
    engineState: XmtpEngineState,
    isSettingsTab: Boolean = false,
    isSearchOpen: Boolean = false,
    searchQuery: String = "",
    isSyncing: Boolean = false,
    isShredding: Boolean = false,
    isCallActive: Boolean = false,
    onSearchOpenChange: (Boolean) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenProtocolInspector: () -> Unit = {},
    onEditContactNickname: () -> Unit = {},
    onStartVoiceCall: () -> Unit = {},
    onStartVideoCall: () -> Unit = {}
) {
    var showChatMenu by remember { mutableStateOf(false) }

    Column {
        TopAppBar(
            title = {
                when {
                    selectedConversation != null -> {
                        val isDm = selectedConversation is Conversation.Dm
                        val isLoopback = selectedConversation is Conversation.Group &&
                            selectedConversation.group.cachedGroupName() == "MLS Research Loopback"
                        val contactKey = if (isDm) selectedConversation.dm.peerInboxId else selectedConversation.id
                        val fallbackLabel = if (isDm) {
                            val peer = selectedConversation.dm.peerInboxId
                            if (peer.length >= 14) "${peer.take(6)}…${peer.takeLast(4)}" else peer
                        } else {
                            selectedConversation.topic.take(16)
                        }
                        val groupName = (selectedConversation as? Conversation.Group)?.group?.cachedGroupName()
                        val resolvedNick = NicknamePolicy.resolveContactNickname(contactKey, nicknameSnapshot)
                        val headerTitle = when {
                            resolvedNick != null -> resolvedNick
                            isDm -> fallbackLabel
                            isLoopback -> "Loopback MLS"
                            !groupName.isNullOrBlank() -> groupName
                            else -> "Ομαδική συνομιλία"
                        }
                        val initials = if (isLoopback) "LB" else ChatFormatting.extractAvatarInitials(headerTitle)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenProtocolInspector() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = initials,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = headerTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Κρυπτογραφημένη συνομιλία",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    isSettingsTab -> {
                        Text(
                            text = "Ρυθμίσεις",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    isSearchOpen -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Αναζήτηση συνομιλιών…",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }

                    else -> {
                        Text(
                            text = "Συνομιλίες",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                if (selectedConversation != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                    }
                } else if (!isSettingsTab && isSearchOpen) {
                    IconButton(
                        onClick = {
                            onSearchQueryChange("")
                            onSearchOpenChange(false)
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Κλείσιμο αναζήτησης")
                    }
                }
            },
            actions = {
                if (selectedConversation != null) {
                    if (selectedConversation is Conversation.Dm && engineState is XmtpEngineState.Ready) {
                        IconButton(
                            onClick = onStartVoiceCall,
                            enabled = !isShredding && !isCallActive
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call_phone),
                                contentDescription = "Κλήση φωνής",
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        IconButton(
                            onClick = onStartVideoCall,
                            enabled = !isShredding && !isCallActive
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_videocam),
                                contentDescription = "Βιντεοκλήση",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showChatMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Επιλογές συνομιλίας")
                        }
                        DropdownMenu(
                            expanded = showChatMenu,
                            onDismissRequest = { showChatMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Πληροφορίες & Πρωτόκολλο MLS") },
                                onClick = {
                                    showChatMenu = false
                                    onOpenProtocolInspector()
                                }
                            )
                            val isLoopback = selectedConversation is Conversation.Group &&
                                selectedConversation.group.cachedGroupName() == "MLS Research Loopback"
                            if (!isLoopback) {
                                DropdownMenuItem(
                                    text = { Text("Ψευδώνυμο επαφής") },
                                    onClick = {
                                        showChatMenu = false
                                        onEditContactNickname()
                                    }
                                )
                            }
                            if (engineState is XmtpEngineState.Ready) {
                                DropdownMenuItem(
                                    text = { Text("Συγχρονισμός μηνυμάτων") },
                                    enabled = !isSyncing && !isShredding,
                                    onClick = {
                                        showChatMenu = false
                                        onSync()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Καταγραφές (Logs & Audit)") },
                                onClick = {
                                    showChatMenu = false
                                    onOpenLogs()
                                }
                            )
                        }
                    }
                } else if (!isSettingsTab && engineState is XmtpEngineState.Ready) {
                    if (isSearchOpen) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Εκκαθάριση")
                            }
                        }
                    } else {
                        IconButton(onClick = { onSearchOpenChange(true) }) {
                            Icon(Icons.Default.Search, contentDescription = "Αναζήτηση")
                        }
                        val rotation by animateFloatAsState(
                            targetValue = if (isSyncing) 360f else 0f,
                            label = "SyncRotation"
                        )
                        IconButton(onClick = onSync, enabled = !isSyncing && !isShredding) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Συγχρονισμός",
                                modifier = Modifier.rotate(rotation)
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun UpdateAvailableBanner(
    latestVersion: String,
    onOpenUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Διαθέσιμη ενημέρωση v$latestVersion",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onOpenUpdate,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("Εγκατάσταση", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun ScreenRecordingShieldOverlay(modifier: Modifier = Modifier) {
    // Bloqueamos toques y ocultamos visualmente el contenido mientras el sistema reporta grabacion de pantalla activa.
    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Προστασία Απορρήτου Ενεργή",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Εντοπίστηκε ενεργή εγγραφή οθόνης. Το περιεχόμενο έχει αποκρυφτεί (FLAG_SECURE).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

internal fun messagingStatusText(message: String): String {
    val normalized = message.lowercase(Locale.ROOT)
    return when {
        normalized.contains("crypto-shredded") || normalized.contains("shred") ->
            "Η οριστική διαγραφή ολοκληρώθηκε (GDPR Art. 17)."
        normalized.contains("loopback") || normalized.contains("ενεργή") ->
            "Η συνεδρία δοκιμής Loopback MLS είναι έτοιμη."
        normalized.contains("αποτυχ") || normalized.contains("σφάλμα") ->
            "Η ενέργεια δεν ολοκληρώθηκε. Προσπαθήστε ξανά."
        normalized.contains("αποστολ") ->
            "Το μήνυμα στάλθηκε."
        else -> message
    }
}
