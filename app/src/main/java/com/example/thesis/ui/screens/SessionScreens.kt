package com.example.thesis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.thesis.ui.components.MessagingNotice
import com.example.thesis.xmtp.XmtpEngineState
import com.example.thesis.xmtp.XmtpInitError

@Composable
internal fun MessagingStartScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ερευνητικό περιβάλλον ασφάλειας με Hardware Keystore KEK και κρυπτογραφημένο SQLCipher Sandbox.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        MessagingNotice(
            title = "Hardware-Backed Ασφάλεια",
            text = "Το Database Encryption Key (DEK) κρυπτογραφείται στο Android Keystore. Συμμόρφωση με GDPR Άρθρο 17 (Right to Erasure)."
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Εκκίνηση Hardware Keystore & XMTP MLS")
        }
    }
}

@Composable
internal fun MessagingLoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(18.dp))
        Text("Hardware DEK Unwrap & Σύνδεση MLS...", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Συνδεόμαστε στο δίκτυο XMTP DEV και προετοιμάζουμε την κρυπτογραφημένη βάση SQLCipher.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
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
        Text("Αποτυχία Σύνδεσης", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Επανάληψη Σύνδεσης")
        }
    }
}
