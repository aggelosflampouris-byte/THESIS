package com.example.thesis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.repository.ConversationRepository
import com.example.thesis.repository.DecryptedMessage
import com.example.thesis.security.CryptoShredder
import com.example.thesis.security.KeystoreManager
import com.example.thesis.security.ShredResult
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.XMTPEnvironment

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val keystoreManager = KeystoreManager(appContext)
    private val databaseFactory = DatabaseFactory(appContext)
    private val cryptoShredder = CryptoShredder(appContext, keystoreManager, databaseFactory)
    private val engine = XmtpEngine(appContext, XMTPEnvironment.DEV)
    private val repository = ConversationRepository(engine, viewModelScope)

    private var activeSigner: ThesisSigner? = null
    private var messagesJob: Job? = null

    val engineState: StateFlow<XmtpEngineState> = engine.state

    private val _walletAddress = MutableStateFlow<String?>(null)
    val walletAddress: StateFlow<String?> = _walletAddress.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _selectedConversation = MutableStateFlow<Conversation?>(null)
    val selectedConversation: StateFlow<Conversation?> = _selectedConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<DecryptedMessage>>(emptyList())
    val messages: StateFlow<List<DecryptedMessage>> = _messages.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeConversations().collectLatest { list ->
                _conversations.value = list
            }
        }
    }

    /**
     * Εκκινεί την αλυσίδα ασφαλείας: Hardware DEK -> SQLCipher Sandbox -> EIP-191 Signer -> XMTP MLS.
     */
    fun initializeSession() {
        viewModelScope.launch {
            val dekResult = keystoreManager.getOrCreateDatabaseKey()
            val dekBytes = try {
                dekResult.getOrThrow()
            } catch (e: Throwable) {
                _statusBanner.value = "Σφάλμα Hardware Keystore: ${e.message}"
                return@launch
            }

            try {
                val signer = activeSigner ?: ThesisSigner.createEphemeral().also { activeSigner = it }
                _walletAddress.value = signer.address

                val dbFile = databaseFactory.resolveDatabaseFile(signer.address)
                val dbDirectory = dbFile.parentFile ?: appContext.filesDir

                engine.initialise(
                    signer = signer,
                    dbDirectory = dbDirectory,
                    dbEncryptionKey = dekBytes.copyOf()
                )
                _statusBanner.value = "Σύνδεση με Hardware-Backed SQLCipher & MLS..."
            } finally {
                // Άμεσος μηδενισμός του DEK στη μνήμη RAM
                dekBytes.fill(0x00)
            }
        }
    }

    fun selectConversation(conversation: Conversation?) {
        _selectedConversation.value = conversation
        messagesJob?.cancel()
        _messages.value = emptyList()

        if (conversation != null) {
            messagesJob = viewModelScope.launch {
                repository.observeMessages(conversation).collectLatest { msgList ->
                    _messages.value = msgList
                }
            }
        }
    }

    fun startNewConversation(peerAddress: String) {
        viewModelScope.launch {
            repository.createConversation(peerAddress)
                .onSuccess { conv ->
                    _statusBanner.value = "Η συνομιλία MLS δημιουργήθηκε."
                    selectConversation(conv)
                }
                .onFailure { err ->
                    _statusBanner.value = "Αποτυχία δημιουργίας συνομιλίας: ${err.message}"
                }
        }
    }

    fun sendMessage(text: String) {
        val conv = _selectedConversation.value ?: return
        viewModelScope.launch {
            repository.sendMessage(conv, text)
                .onFailure { err ->
                    _statusBanner.value = "Αποτυχία αποστολής: ${err.message}"
                }
        }
    }

    /**
     * Εκτελεί GDPR Άρθρο 17 Crypto-Shredding:
     * 1. Τερματίζει τον XMTP Engine.
     * 2. Καταστρέφει το Hardware KEK στο Android Keystore.
     * 3. Διαγράφει φυσικά τα αρχεία της SQLCipher (.db, -wal, -shm, -journal).
     */
    fun performCryptoShredding() {
        val address = _walletAddress.value ?: return
        viewModelScope.launch {
            messagesJob?.cancel()
            _selectedConversation.value = null
            _messages.value = emptyList()
            _conversations.value = emptyList()

            engine.shutdown()
            activeSigner?.close()
            activeSigner = null

            when (val res = cryptoShredder.shredAccountData(address)) {
                is ShredResult.Success -> {
                    _walletAddress.value = null
                    _statusBanner.value = "GDPR Wipe: Κλειδί Keystore & ${res.deletedFilesCount} αρχεία βάσης καταστράφηκαν οριστικά."
                }
                is ShredResult.PartialSuccessCryptoShreddedOnly -> {
                    _walletAddress.value = null
                    _statusBanner.value = "Crypto-Shredded: ${res.message}"
                }
                is ShredResult.PartialSuccess -> {
                    _walletAddress.value = null
                    _statusBanner.value = "Crypto-Shredded: ${res.message}"
                }
                is ShredResult.Failure -> {
                    _statusBanner.value = "Σφάλμα κατά το Crypto-Shredding."
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.shutdown()
        activeSigner?.close()
    }
}
