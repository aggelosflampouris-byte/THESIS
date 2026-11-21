package com.example.thesis.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.R
import com.example.thesis.call.CallTransportType
import com.example.thesis.call.CallUiState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun ActiveCallOverlay(
    callState: CallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismissEnded: () -> Unit,
    onMinimize: () -> Unit = {}
) {
    if (callState is CallUiState.Idle) return

    var elapsedSeconds by remember(callState.callId, callState is CallUiState.Active) {
        mutableLongStateOf(0L)
    }
    if (callState is CallUiState.Active) {
        LaunchedEffect(callState.callId, callState.connectedAtElapsedMs) {
            while (true) {
                elapsedSeconds = ((SystemClock.elapsedRealtime() - callState.connectedAtElapsedMs) / 1_000L)
                    .coerceAtLeast(0L)
                delay(1_000L)
            }
        }
    }

    val statusText = when (callState) {
        is CallUiState.OutgoingRinging -> "Κλήση..."
        is CallUiState.IncomingRinging -> "Εισερχόμενη κλήση φωνής"
        is CallUiState.Connecting -> "Διαπραγμάτευση DTLS-SRTP..."
        is CallUiState.Active -> formatCallDuration(elapsedSeconds)
        is CallUiState.Ended -> callState.reasonText
        CallUiState.Idle -> ""
    }

    val sasCode = when (callState) {
        is CallUiState.Connecting -> callState.sasCode
        is CallUiState.Active -> callState.sasCode
        else -> null
    }

    val transportLabel = when (callState) {
        is CallUiState.Active -> when (callState.transportType) {
            CallTransportType.DIRECT_P2P -> "Απευθείας P2P"
            CallTransportType.RELAYED_TURN -> "TURN Relay"
            CallTransportType.UNKNOWN -> "WebRTC + MLS"
        }
        else -> "MLS + DTLS-SRTP"
    }

    // Superficie opaca bajo ScreenRecordingShieldOverlay para que el escudo siga cubriendo el SAS si se graba pantalla.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0E151B),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = if (callState is CallUiState.Ended) onDismissEnded else onMinimize,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Ελαχιστοποίηση",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (callState is CallUiState.Ended) "Κλείσιμο" else "Οθόνη Εφαρμογής",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFF1B2B36)
                    ) {
                        Text(
                            text = transportLabel,
                            color = Color(0xFF7CE8A5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1F3442)),
                    contentAlignment = Alignment.Center
                ) {
                    val initial = callState.peerDisplayName
                        .firstOrNull { it.isLetterOrDigit() }
                        ?.uppercaseChar()
                        ?.toString()
                        ?: "?"
                    Text(
                        text = initial,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = callState.peerDisplayName.ifBlank { "Επαφή XMTP" },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = statusText,
                    fontSize = 16.sp,
                    color = Color(0xFFB4C5D0),
                    fontFamily = if (callState is CallUiState.Active) FontFamily.Monospace else FontFamily.Default,
                    textAlign = TextAlign.Center
                )
            }

            if (!sasCode.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF16222C),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Επαλήθευση κρυπτογράφησης φωνής (SAS)",
                            fontSize = 12.sp,
                            color = Color(0xFF9BB0BE),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val groupedSas = if (sasCode.length == 6) {
                            "${sasCode.substring(0, 3)} ${sasCode.substring(3, 6)}"
                        } else {
                            sasCode
                        }
                        Text(
                            text = groupedSas,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 3.sp,
                            color = Color(0xFF8BE9FD)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            when (callState) {
                is CallUiState.IncomingRinging -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = onDecline,
                                shape = CircleShape,
                                modifier = Modifier.size(68.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9383A))
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_call_end),
                                    contentDescription = "Απόρριψη",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "Απόρριψη", fontSize = 13.sp, color = Color(0xFFB4C5D0))
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = onAccept,
                                shape = CircleShape,
                                modifier = Modifier.size(68.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23A55A))
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_call_phone),
                                    contentDescription = "Απάντηση",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "Απάντηση", fontSize = 13.sp, color = Color(0xFFB4C5D0))
                        }
                    }
                }

                is CallUiState.Active -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                FilledIconToggleButton(
                                    checked = callState.isMuted,
                                    onCheckedChange = { onToggleMute() },
                                    modifier = Modifier.size(58.dp),
                                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                                        containerColor = Color(0xFF1F2D38),
                                        contentColor = Color.White,
                                        checkedContainerColor = Color(0xFFE0A82E),
                                        checkedContentColor = Color.Black
                                    )
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (callState.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on
                                        ),
                                        contentDescription = "Σίγαση",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (callState.isMuted) "Απενεργ." else "Σίγαση",
                                    fontSize = 12.sp,
                                    color = Color(0xFFB4C5D0)
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                FilledIconToggleButton(
                                    checked = callState.isSpeakerOn,
                                    onCheckedChange = { onToggleSpeaker() },
                                    modifier = Modifier.size(58.dp),
                                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                                        containerColor = Color(0xFF1F2D38),
                                        contentColor = Color.White,
                                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                                        checkedContentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_speaker_on),
                                        contentDescription = "Ηχείο",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(text = "Ηχείο", fontSize = 12.sp, color = Color(0xFFB4C5D0))
                            }
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Button(
                            onClick = onHangup,
                            shape = CircleShape,
                            modifier = Modifier.size(68.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9383A))
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call_end),
                                contentDescription = "Τερματισμός κλήσης",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                is CallUiState.OutgoingRinging,
                is CallUiState.Connecting -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Button(
                            onClick = onHangup,
                            shape = CircleShape,
                            modifier = Modifier.size(68.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9383A))
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call_end),
                                contentDescription = "Ακύρωση κλήσης",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Τερματισμός", fontSize = 13.sp, color = Color(0xFFB4C5D0))
                    }
                }

                is CallUiState.Ended -> {
                    TextButton(
                        onClick = onDismissEnded,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = "Κλείσιμο",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                CallUiState.Idle -> Unit
            }
        }
    }
}

@Composable
internal fun MinimizedCallBanner(
    callState: CallUiState,
    onExpand: () -> Unit,
    onAccept: () -> Unit,
    onDeclineOrHangup: () -> Unit
) {
    if (callState is CallUiState.Idle) return
    val statusLabel = when (callState) {
        is CallUiState.IncomingRinging -> "Εισερχόμενη κλήση"
        is CallUiState.OutgoingRinging -> "Κλήση σε εξέλιξη..."
        is CallUiState.Connecting -> "Σύνδεση DTLS-SRTP..."
        is CallUiState.Active -> "Ενεργή κλήση φωνής"
        is CallUiState.Ended -> callState.reasonText
        CallUiState.Idle -> ""
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
        color = Color(0xFF142834),
        contentColor = Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${callState.peerDisplayName.ifBlank { "Επαφή XMTP" }} • $statusLabel",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Πατήστε για εμφάνιση οθόνης κλήσης",
                    fontSize = 10.sp,
                    color = Color(0xFF9BB0BE),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (callState is CallUiState.IncomingRinging) {
                    Button(
                        onClick = onAccept,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23A55A)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Απάντηση", fontSize = 11.sp, color = Color.White)
                    }
                }
                Button(
                    onClick = onDeclineOrHangup,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9383A)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (callState is CallUiState.IncomingRinging) "Απόρριψη" else "Τέλος",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

private fun formatCallDuration(totalSeconds: Long): String {
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
