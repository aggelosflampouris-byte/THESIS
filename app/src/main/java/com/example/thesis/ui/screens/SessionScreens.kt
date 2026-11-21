package com.example.thesis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thesis.security.ShredAuditProof
import com.example.thesis.ui.components.MessagingNotice
import com.example.thesis.xmtp.XmtpEngineState
import com.example.thesis.xmtp.XmtpInitError

@Composable
internal fun MessagingStartScreen(
    onStart: () -> Unit,
    onReceiveFromOldDevice: () -> Unit = {},
    onOpenQrDownload: () -> Unit = {},
    lastShredProof: ShredAuditProof? = null,
    onCopyShredProof: (ShredAuditProof) -> Unit = {},
    onOpenLogs: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Ασφαλής Επικοινωνία MLS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Ερευνητικό περιβάλλον ασφάλειας με Hardware Keystore KEK και κρυπτογραφημένο SQLCipher Sandbox.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            MessagingNotice(
                title = "Hardware-Backed Ασφάλεια",
                text = "Το Database Encryption Key (DEK) κρυπτογραφείται στο Android Keystore. Συμμόρφωση με GDPR Άρθρο 17 (Right to Erasure)."
            )

            if (lastShredProof != null) {
                Spacer(Modifier.height(14.dp))
                ShredAuditCertificateCard(
                    proof = lastShredProof,
                    onCopyCertificate = { onCopyShredProof(lastShredProof) },
                    onOpenLogs = onOpenLogs
                )
            }

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    text = "Εκκίνηση Hardware Keystore & XMTP MLS",
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onReceiveFromOldDevice,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    text = "Μεταφορά από Παλιά Συσκευή (QR)",
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenQrDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Λήψη Τελευταίας Έκδοσης (QR)",
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ShredAuditCertificateCard(
    proof: ShredAuditProof,
    onCopyCertificate: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "GDPR Art. 17 Audit Certificate",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (proof.isCompliant) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (proof.isCompliant) "COMPLIANT" else "PARTIAL",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (proof.isCompliant) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = "Keystore KEK Purged: ${proof.isKeystoreEntryPurged} • SQLCipher DB Unlinked: ${proof.isPrimaryDatabaseUnlinked} • Artifacts: ${proof.deletedArtifactsCount}",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
            ) {
                Text(
                    text = "SHA-256: ${proof.certificateSha256}",
                    modifier = Modifier.padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyCertificate,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Αντιγραφή Πιστοποιητικού", fontSize = 11.sp, maxLines = 1)
                }
                TextButton(
                    onClick = onOpenLogs,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("Προβολή Logs", fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun MessagingLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Hardware DEK Unwrap & Σύνδεση MLS...",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Συνδεόμαστε στο δίκτυο XMTP DEV και προετοιμάζουμε την κρυπτογραφημένη βάση SQLCipher.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun MessagingConnectionError(state: XmtpEngineState.Error, onRetry: () -> Unit) {
    val explanation = when (state.error) {
        is XmtpInitError.SignatureRejected ->
            "Η υπογραφή Secp256k1 ακυρώθηκε ή δεν έγινε αποδεκτή."
        is XmtpInitError.NetworkFailure ->
            "Δεν ήταν δυνατή η σύνδεση gRPC με τους κόμβους του δικτύου XMTP."
        is XmtpInitError.DatabaseError ->
            "Αποτυχία εκκίνησης του κρυπτογραφημένου SQLCipher database sandbox."
        is XmtpInitError.IdentityError ->
            "Αποτυχία εγγραφής του identity installation στο MLS ledger."
        is XmtpInitError.Unknown ->
            state.error.message ?: "Παρουσιάστηκε απρόσμενο πρόβλημα κατά τη σύνδεση."
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Αποτυχία Σύνδεσης",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = explanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Επανάληψη Σύνδεσης", textAlign = TextAlign.Center)
            }
        }
    }
}
