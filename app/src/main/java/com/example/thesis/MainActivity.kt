package com.example.thesis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.repository.ConversationRepository
import com.example.thesis.ui.screens.ChatScreen
import com.example.thesis.ui.screens.InboxScreen
import com.example.thesis.ui.theme.THESISTheme
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import org.xmtp.android.library.XMTPEnvironment

class MainActivity : ComponentActivity() {

    private lateinit var engine: XmtpEngine
    private lateinit var repository: ConversationRepository
    private var signer: ThesisSigner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Provision ephemeral dev signer for dev environment handshakes
        val activeSigner = ThesisSigner.generateRandom()
        this.signer = activeSigner

        // Instantiate core engine and repository singletons
        this.engine = XmtpEngine(
            context = applicationContext,
            environment = XMTPEnvironment.DEV
        )
        this.repository = ConversationRepository(xmtpEngine = engine)

        setContent {
            THESISTheme {
                ThesisApp(
                    engine = engine,
                    repository = repository,
                    signer = activeSigner
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::engine.isInitialized) {
                engine.shutdown()
            }
        } finally {
            try {
                if (::repository.isInitialized) {
                    repository.close()
                }
            } finally {
                signer?.close()
                signer = null
            }
        }
    }
}

@Composable
fun ThesisApp(
    engine: XmtpEngine,
    repository: ConversationRepository,
    signer: ThesisSigner,
    modifier: Modifier = Modifier
) {
    val engineState by engine.state.collectAsState()
    var selectedConversationId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (engine.state.value is XmtpEngineState.Idle) {
            engine.initialise(signer)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (val state = engineState) {
            is XmtpEngineState.Idle, is XmtpEngineState.Loading -> {
                InitializationLoadingView()
            }

            is XmtpEngineState.Error -> {
                InitializationErrorView(
                    error = state.error.message,
                    onRetry = { engine.initialise(signer) }
                )
            }

            is XmtpEngineState.Ready -> {
                val currentConvId = selectedConversationId
                if (currentConvId == null) {
                    InboxScreen(
                        repository = repository,
                        currentAddress = signer.address,
                        onConversationClick = { selectedConversationId = it }
                    )
                } else {
                    ChatScreen(
                        repository = repository,
                        conversationId = currentConvId,
                        currentAddress = signer.address,
                        onBackClick = { selectedConversationId = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun InitializationLoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Connecting to XMTP Network",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Authenticating EIP-191 identity and unlocking SQLCipher local store...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun InitializationErrorView(
    error: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Connection Failed",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Retry Handshake")
            }
        }
    }
}
