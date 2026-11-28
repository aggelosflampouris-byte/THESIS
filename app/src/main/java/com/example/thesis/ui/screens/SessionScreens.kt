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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
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
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "Ιδιωτικές Συνομιλίες",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Κρυπτογραφημένα μηνύματα, φωνητικές σημειώσεις και κλήσεις από άκρο σε άκρο.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (lastShredProof != null) {
                Spacer(Modifier.height(20.dp))
                ShredAuditCertificateCard(
                    proof = lastShredProof,
                    onCopyCertificate = { onCopyShredProof(lastShredProof) },
                    onOpenLogs = onOpenLogs
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
            ) {
                Text(
                    text = "Έναρξη",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onReceiveFromOldDevice,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    text = "Μεταφορά από παλιά συσκευή",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenQrDownload) {
                Text(
                    text = "Κοινοποίηση εφαρμογής (QR)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                    text = "Βεβαίωση Διαγραφής (GDPR Art. 17)",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (proof.isCompliant) "ΠΛΗΡΗΣ" else "ΜΕΡΙΚΗ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (proof.isCompliant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = "SHA-256: ${proof.certificateSha256}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onCopyCertificate,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Αντιγραφή", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onOpenLogs,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Καταγραφές", style = MaterialTheme.typography.labelMedium)
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
                .widthIn(max = 360.dp)
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Σύνδεση…",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Ασφαλής προετοιμασία συνεδρίας και τοπικής βάσης.",
                style = MaterialTheme.typography.bodyMedium,
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
            "Η επαλήθευση ταυτότητας ακυρώθηκε."
        is XmtpInitError.NetworkFailure ->
            "Αδυναμία σύνδεσης με το δίκτυο. Ελέγξτε το διαδίκτυο."
        is XmtpInitError.DatabaseError ->
            "Αδυναμία ανοίγματος της τοπικής κρυπτογραφημένης βάσης."
        is XmtpInitError.IdentityError ->
            "Αδυναμία ολοκλήρωσης της εγγραφής ταυτότητας."
        is XmtpInitError.Unknown ->
            state.error.message ?: "Παρουσιάστηκε σφάλμα κατά τη σύνδεση."
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Αποτυχία σύνδεσης",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Δοκιμή ξανά", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
