package com.example.thesis.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.repository.ConversationRepository
import com.example.thesis.repository.IncomingCallSignal
import com.example.thesis.xmtp.CallIceCandidate
import com.example.thesis.xmtp.CallSignal
import com.example.thesis.xmtp.CallSignalType
import com.example.thesis.xmtp.XmtpEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmtp.android.library.Conversation
import java.util.UUID

sealed interface CallUiState {
    val callId: String
    val conversationId: String
    val peerInboxId: String
    val peerDisplayName: String

    data object Idle : CallUiState {
        override val callId: String = ""
        override val conversationId: String = ""
        override val peerInboxId: String = ""
        override val peerDisplayName: String = ""
    }

    data class OutgoingRinging(
        override val callId: String,
        override val conversationId: String,
        override val peerInboxId: String,
        override val peerDisplayName: String
    ) : CallUiState

    data class IncomingRinging(
        override val callId: String,
        override val conversationId: String,
        override val peerInboxId: String,
        override val peerDisplayName: String
    ) : CallUiState

    data class Connecting(
        override val callId: String,
        override val conversationId: String,
        val isOutgoing: Boolean,
        override val peerInboxId: String,
        override val peerDisplayName: String,
        val sasCode: String? = null
    ) : CallUiState

    data class Active(
        override val callId: String,
        override val conversationId: String,
        override val peerInboxId: String,
        override val peerDisplayName: String,
        val connectedAtElapsedMs: Long,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val sasCode: String? = null,
        val transportType: CallTransportType = CallTransportType.UNKNOWN
    ) : CallUiState

    data class Ended(
        override val callId: String,
        override val conversationId: String,
        override val peerInboxId: String,
        override val peerDisplayName: String,
        val reasonText: String
    ) : CallUiState
}

// Orquestador unico de estado para llamadas 1-a-1 WebRTC + XMTP con cola FIFO y temporizadores de timbre/conexion.
@OptIn(ExperimentalCoroutinesApi::class)
internal class CallSessionManager(
    private val appContext: Context,
    private val engine: XmtpEngine,
    private val repository: ConversationRepository,
    private val nicknameSnapshot: StateFlow<NicknameSnapshot>,
    private val notificationManager: CallNotificationManager,
    private val conversationsSnapshotProvider: () -> List<Conversation>,
    private val diagnosticsLogger: (tag: String, message: String, isError: Boolean) -> Unit = { _, _, _ -> }
) : WebRtcCallListener {

    private data class ActiveSession(
        val callId: String,
        val conversationId: String,
        val peerInboxId: String,
        val peerDisplayName: String,
        val isOutgoing: Boolean,
        var phase: SessionPhase,
        var remoteOfferSdp: String? = null,
        val bufferedRemoteIce: MutableList<CallIceCandidate> = mutableListOf(),
        var localFingerprint: String? = null,
        var remoteFingerprint: String? = null,
        var sasCode: String? = null,
        var transportType: CallTransportType = CallTransportType.UNKNOWN,
        var connectedAtElapsedMs: Long? = null,
        var isMuted: Boolean = false,
        var isSpeakerOn: Boolean = false,
        val outboundSignalChannel: Channel<CallSignal> = Channel(Channel.UNLIMITED),
        var signalSenderJob: Job? = null
    )

    private enum class SessionPhase {
        OUTGOING_RINGING,
        INCOMING_RINGING,
        CONNECTING,
        ACTIVE
    }

    private val stateDispatcher = Dispatchers.Default.limitedParallelism(1)
    // Sin este manejador cualquier excepcion en una corrutina de llamada cerraria toda la aplicacion.
    private val callExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        emergencyReset(throwable)
    }
    private val managerScope = CoroutineScope(SupervisorJob() + stateDispatcher + callExceptionHandler)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + callExceptionHandler)

    private val audioController = CallAudioController(appContext)
    private val webrtcEngine = WebRtcCallEngine(appContext = appContext, listener = this)

    private val _callState = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val callState: StateFlow<CallUiState> = _callState.asStateFlow()

    private var activeSession: ActiveSession? = null
    private var ringTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var endedDismissJob: Job? = null

    init {
        managerScope.launch {
            repository.incomingCallSignals.collect { incoming ->
                handleIncomingSignalOnDispatcher(incoming)
            }
        }
    }

    fun startOutgoingCall(conversation: Conversation) {
        if (conversation !is Conversation.Dm) return
        if (!CallForegroundService.hasRecordAudioPermission(appContext)) return

        val peerInboxId = conversation.dm.peerInboxId
        val callId = UUID.randomUUID().toString()
        val peerName = resolvePeerDisplayName(conversation.id, peerInboxId)

        managerScope.launch {
            if (activeSession != null) return@launch
            endedDismissJob?.cancel()

            val session = ActiveSession(
                callId = callId,
                conversationId = conversation.id,
                peerInboxId = peerInboxId,
                peerDisplayName = peerName,
                isOutgoing = true,
                phase = SessionPhase.OUTGOING_RINGING
            )
            startSignalSenderLocked(session)
            activeSession = session
            _callState.value = CallUiState.OutgoingRinging(
                callId = callId,
                conversationId = conversation.id,
                peerInboxId = peerInboxId,
                peerDisplayName = peerName
            )

            CallForegroundService.start(
                context = appContext,
                callId = callId,
                conversationId = conversation.id,
                peerName = peerName,
                statusLabel = "Κλήση σε εξέλιξη..."
            )
            audioController.startInCallAudio(speakerOn = false)
            scheduleRingTimeoutLocked(callId, isOutgoing = true)
            diagnosticsLogger("CALL", "Εξερχόμενη κλήση [call=${callId.take(8)}]", false)
            webrtcEngine.startOutgoingOffer(callId)
        }
    }

    fun acceptIncomingCall(targetCallId: String? = null) {
        if (!CallForegroundService.hasRecordAudioPermission(appContext)) return
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.phase != SessionPhase.INCOMING_RINGING) return@launch
            if (!targetCallId.isNullOrBlank() && session.callId != targetCallId) return@launch
            val offerSdp = session.remoteOfferSdp ?: return@launch

            ringTimeoutJob?.cancel()
            audioController.stopRinging()
            notificationManager.cancelIncomingCallNotification()

            session.phase = SessionPhase.CONNECTING
            _callState.value = CallUiState.Connecting(
                callId = session.callId,
                conversationId = session.conversationId,
                isOutgoing = false,
                peerInboxId = session.peerInboxId,
                peerDisplayName = session.peerDisplayName,
                sasCode = session.sasCode
            )

            CallForegroundService.start(
                context = appContext,
                callId = session.callId,
                conversationId = session.conversationId,
                peerName = session.peerDisplayName,
                statusLabel = "Σύνδεση κλήσης..."
            )
            audioController.startInCallAudio(speakerOn = session.isSpeakerOn)
            scheduleConnectTimeoutLocked(session.callId)

            val initialIce = session.bufferedRemoteIce.toList()
            session.bufferedRemoteIce.clear()
            webrtcEngine.acceptIncomingOfferAndCreateAnswer(
                callId = session.callId,
                remoteOfferSdp = offerSdp,
                initialRemoteIce = initialIce
            )
        }
    }

    fun declineIncomingCall(targetCallId: String? = null) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (!targetCallId.isNullOrBlank() && session.callId != targetCallId) return@launch
            if (session.phase != SessionPhase.INCOMING_RINGING) return@launch

            val signal = CallSignal(
                callId = session.callId,
                type = CallSignalType.REJECT,
                timestampMs = System.currentTimeMillis(),
                reason = "declined"
            )
            teardownSessionLocked(
                endReasonText = null,
                signalToSend = signal,
                targetConvId = session.conversationId
            )
        }
    }

    fun hangup(targetCallId: String? = null) {
        managerScope.launch {
            val session = activeSession ?: run {
                notificationManager.cancelAllCallNotifications()
                CallForegroundService.stop(appContext)
                audioController.release()
                return@launch
            }
            if (!targetCallId.isNullOrBlank() && session.callId != targetCallId) return@launch

            val signalType = if (session.phase == SessionPhase.INCOMING_RINGING) {
                CallSignalType.REJECT
            } else {
                CallSignalType.HANGUP
            }
            val signal = CallSignal(
                callId = session.callId,
                type = signalType,
                timestampMs = System.currentTimeMillis(),
                reason = "hangup"
            )
            teardownSessionLocked(
                endReasonText = "Η κλήση τερματίστηκε",
                signalToSend = signal,
                targetConvId = session.conversationId
            )
        }
    }

    fun toggleMute() {
        managerScope.launch {
            val session = activeSession ?: return@launch
            val nextMuted = !session.isMuted
            session.isMuted = nextMuted
            webrtcEngine.setMicrophoneMuted(nextMuted)
            val current = _callState.value
            if (current is CallUiState.Active && current.callId == session.callId) {
                _callState.value = current.copy(isMuted = nextMuted)
            }
        }
    }

    fun toggleSpeaker() {
        managerScope.launch {
            val session = activeSession ?: return@launch
            val nextSpeaker = !session.isSpeakerOn
            session.isSpeakerOn = nextSpeaker
            audioController.setSpeakerEnabled(nextSpeaker)
            val current = _callState.value
            if (current is CallUiState.Active && current.callId == session.callId) {
                _callState.value = current.copy(isSpeakerOn = nextSpeaker)
            }
        }
    }

    fun dismissEndedState() {
        managerScope.launch {
            endedDismissJob?.cancel()
            if (_callState.value is CallUiState.Ended) {
                _callState.value = CallUiState.Idle
            }
        }
    }

    fun forceTeardownOnGdprShred() {
        // Limpieza sincrona inmediata antes de destruir identidad y base de datos SQLCipher.
        ringTimeoutJob?.cancel()
        connectTimeoutJob?.cancel()
        endedDismissJob?.cancel()
        audioController.release()
        notificationManager.cancelAllCallNotifications()
        CallForegroundService.stop(appContext)
        _callState.value = CallUiState.Idle
        webrtcEngine.disposeSession(null)
        managerScope.launch {
            activeSession?.let { old ->
                old.outboundSignalChannel.close()
                old.signalSenderJob?.cancel()
            }
            activeSession = null
        }
    }

    override fun onLocalOfferCreated(callId: String, sdp: String) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            session.localFingerprint = CallSignalingPolicy.extractDtlsSha256Fingerprint(sdp)
            session.outboundSignalChannel.trySend(
                CallSignal(
                    callId = callId,
                    type = CallSignalType.OFFER,
                    timestampMs = System.currentTimeMillis(),
                    sdp = sdp
                )
            )
        }
    }

    override fun onLocalAnswerCreated(callId: String, sdp: String) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            session.localFingerprint = CallSignalingPolicy.extractDtlsSha256Fingerprint(sdp)
            session.outboundSignalChannel.trySend(
                CallSignal(
                    callId = callId,
                    type = CallSignalType.ANSWER,
                    timestampMs = System.currentTimeMillis(),
                    sdp = sdp
                )
            )
        }
    }

    override fun onIceCandidateBatchReady(callId: String, candidates: List<CallIceCandidate>) {
        if (candidates.isEmpty()) return
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            session.outboundSignalChannel.trySend(
                CallSignal(
                    callId = callId,
                    type = CallSignalType.ICE_BATCH,
                    timestampMs = System.currentTimeMillis(),
                    iceCandidates = candidates
                )
            )
        }
    }

    override fun onIceConnected(callId: String) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            connectTimeoutJob?.cancel()
            ringTimeoutJob?.cancel()

            val startedAt = session.connectedAtElapsedMs ?: SystemClock.elapsedRealtime().also {
                session.connectedAtElapsedMs = it
            }
            session.phase = SessionPhase.ACTIVE
            _callState.value = CallUiState.Active(
                callId = session.callId,
                conversationId = session.conversationId,
                peerInboxId = session.peerInboxId,
                peerDisplayName = session.peerDisplayName,
                connectedAtElapsedMs = startedAt,
                isMuted = session.isMuted,
                isSpeakerOn = session.isSpeakerOn,
                sasCode = session.sasCode,
                transportType = session.transportType
            )
            notificationManager.updateOngoingCallNotification(
                callId = session.callId,
                conversationId = session.conversationId,
                peerName = session.peerDisplayName,
                statusLabel = "Κλήση φωνής σε εξέλιξη (DTLS-SRTP)"
            )
        }
    }

    override fun onIceDisconnected(callId: String, reason: String) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            teardownSessionLocked(
                endReasonText = "Η σύνδεση διακόπηκε",
                signalToSend = null,
                targetConvId = session.conversationId
            )
        }
    }

    override fun onTransportTypeChanged(callId: String, transportType: CallTransportType) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            session.transportType = transportType
            val current = _callState.value
            if (current is CallUiState.Active && current.callId == callId) {
                _callState.value = current.copy(transportType = transportType)
            }
        }
    }

    override fun onFingerprintsUpdated(
        callId: String,
        localFingerprint: String?,
        remoteFingerprint: String?
    ) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            if (!localFingerprint.isNullOrBlank()) session.localFingerprint = localFingerprint
            if (!remoteFingerprint.isNullOrBlank()) session.remoteFingerprint = remoteFingerprint
            val localInboxId = engine.client?.inboxId.orEmpty()
            val derived = CallSignalingPolicy.deriveVoiceSasCode(
                callId = callId,
                localFingerprint = session.localFingerprint,
                remoteFingerprint = session.remoteFingerprint,
                localInboxId = localInboxId,
                remoteInboxId = session.peerInboxId
            ) ?: return@launch
            session.sasCode = derived
            when (val current = _callState.value) {
                is CallUiState.Connecting -> if (current.callId == callId) {
                    _callState.value = current.copy(sasCode = derived)
                }
                is CallUiState.Active -> if (current.callId == callId) {
                    _callState.value = current.copy(sasCode = derived)
                }
                else -> Unit
            }
        }
    }

    override fun onEngineError(callId: String, reason: String) {
        Log.e(TAG, "WebRTC error on call=$callId: $reason")
        diagnosticsLogger("CALL", "Σφάλμα WebRTC [call=${callId.take(8)}]: $reason", true)
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            val hangupSignal = CallSignal(
                callId = callId,
                type = CallSignalType.HANGUP,
                timestampMs = System.currentTimeMillis(),
                reason = "error"
            )
            teardownSessionLocked(
                endReasonText = "Σφάλμα ήχου WebRTC",
                signalToSend = hangupSignal,
                targetConvId = session.conversationId
            )
        }
    }

    private suspend fun handleIncomingSignalOnDispatcher(incoming: IncomingCallSignal) {
        val signal = incoming.signal
        when (signal.type) {
            CallSignalType.OFFER -> handleIncomingOfferLocked(incoming)
            CallSignalType.ANSWER -> handleIncomingAnswerLocked(incoming)
            CallSignalType.ICE_BATCH -> handleIncomingIceBatchLocked(incoming)
            CallSignalType.REJECT -> handleRemoteTerminalSignalLocked(
                incoming = incoming,
                endMessage = "Η κλήση απορρίφθηκε"
            )
            CallSignalType.BUSY -> handleRemoteTerminalSignalLocked(
                incoming = incoming,
                endMessage = "Ο συνομιλητής είναι κατειλημμένος"
            )
            CallSignalType.HANGUP -> handleRemoteTerminalSignalLocked(
                incoming = incoming,
                endMessage = "Ο συνομιλητής έκλεισε"
            )
        }
    }

    private suspend fun handleIncomingOfferLocked(incoming: IncomingCallSignal) {
        val signal = incoming.signal
        val offerSdp = signal.sdp ?: return
        val offerTs = signal.timestampMs.takeIf { it > 0L } ?: incoming.sentAtMs
        if (CallSignalingPolicy.isOfferExpired(offerTimestampMs = offerTs)) {
            Log.i(TAG, "Ignoring expired OFFER for callId=${signal.callId}")
            return
        }

        val conversation = resolveDmConversation(incoming.conversationId) ?: return
        val localInboxId = engine.client?.inboxId.orEmpty()
        val current = activeSession

        if (current != null) {
            // Resolvemos colision simultanea de ofertas (glare) con desempate determinista por inboxId.
            val isSamePeerGlare = current.phase == SessionPhase.OUTGOING_RINGING &&
                current.peerInboxId.equals(incoming.senderInboxId, ignoreCase = true)

            if (isSamePeerGlare) {
                val glareDecision = CallSignalingPolicy.resolveGlare(localInboxId, incoming.senderInboxId)
                if (glareDecision == GlareResolution.KEEP_LOCAL_OFFER) {
                    return
                }
                val yieldHangup = CallSignal(
                    callId = current.callId,
                    type = CallSignalType.HANGUP,
                    timestampMs = System.currentTimeMillis(),
                    reason = "glare_yield"
                )
                teardownSessionLocked(
                    endReasonText = null,
                    signalToSend = yieldHangup,
                    targetConvId = current.conversationId
                )
            } else if (CallSignalingPolicy.shouldAutoReplyBusy(current.callId, signal.callId)) {
                sendSignalFireAndForget(
                    conversationId = incoming.conversationId,
                    signal = CallSignal(
                        callId = signal.callId,
                        type = CallSignalType.BUSY,
                        timestampMs = System.currentTimeMillis(),
                        reason = "busy"
                    )
                )
                return
            } else {
                return
            }
        }

        endedDismissJob?.cancel()
        val peerName = resolvePeerDisplayName(conversation.id, incoming.senderInboxId)
        val session = ActiveSession(
            callId = signal.callId,
            conversationId = conversation.id,
            peerInboxId = incoming.senderInboxId,
            peerDisplayName = peerName,
            isOutgoing = false,
            phase = SessionPhase.INCOMING_RINGING,
            remoteOfferSdp = offerSdp,
            remoteFingerprint = CallSignalingPolicy.extractDtlsSha256Fingerprint(offerSdp)
        )
        startSignalSenderLocked(session)
        activeSession = session
        _callState.value = CallUiState.IncomingRinging(
            callId = session.callId,
            conversationId = session.conversationId,
            peerInboxId = session.peerInboxId,
            peerDisplayName = session.peerDisplayName
        )

        audioController.startIncomingRinging()
        notificationManager.showIncomingCallNotification(
            callId = session.callId,
            conversationId = session.conversationId,
            callerName = session.peerDisplayName
        )
        scheduleRingTimeoutLocked(session.callId, isOutgoing = false)
    }

    private fun handleIncomingAnswerLocked(incoming: IncomingCallSignal) {
        val session = activeSession ?: return
        if (session.callId != incoming.signal.callId) return
        if (!session.isOutgoing || session.phase != SessionPhase.OUTGOING_RINGING) return
        val answerSdp = incoming.signal.sdp ?: return

        ringTimeoutJob?.cancel()
        session.phase = SessionPhase.CONNECTING
        _callState.value = CallUiState.Connecting(
            callId = session.callId,
            conversationId = session.conversationId,
            isOutgoing = true,
            peerInboxId = session.peerInboxId,
            peerDisplayName = session.peerDisplayName,
            sasCode = session.sasCode
        )
        scheduleConnectTimeoutLocked(session.callId)
        webrtcEngine.applyRemoteAnswer(session.callId, answerSdp)
    }

    private fun handleIncomingIceBatchLocked(incoming: IncomingCallSignal) {
        val session = activeSession ?: return
        if (session.callId != incoming.signal.callId) return
        val candidates = incoming.signal.iceCandidates
        if (candidates.isEmpty()) return

        if (session.phase == SessionPhase.INCOMING_RINGING) {
            session.bufferedRemoteIce.addAll(candidates)
        } else {
            webrtcEngine.addRemoteIceCandidates(session.callId, candidates)
        }
    }

    private suspend fun handleRemoteTerminalSignalLocked(
        incoming: IncomingCallSignal,
        endMessage: String
    ) {
        val session = activeSession ?: return
        if (session.callId != incoming.signal.callId) return
        teardownSessionLocked(
            endReasonText = endMessage,
            signalToSend = null,
            targetConvId = session.conversationId
        )
    }

    private fun scheduleRingTimeoutLocked(callId: String, isOutgoing: Boolean) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = managerScope.launch {
            delay(CallSignalingPolicy.RING_TIMEOUT_MS)
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            if (session.phase != SessionPhase.OUTGOING_RINGING && session.phase != SessionPhase.INCOMING_RINGING) {
                return@launch
            }
            val endSignal = if (isOutgoing) {
                CallSignal(
                    callId = callId,
                    type = CallSignalType.HANGUP,
                    timestampMs = System.currentTimeMillis(),
                    reason = "timeout"
                )
            } else null
            teardownSessionLocked(
                endReasonText = "Αναπάντητη κλήση",
                signalToSend = endSignal,
                targetConvId = session.conversationId
            )
        }
    }

    private fun scheduleConnectTimeoutLocked(callId: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = managerScope.launch {
            delay(CallSignalingPolicy.CONNECT_TIMEOUT_MS)
            val session = activeSession ?: return@launch
            if (session.callId != callId || session.phase != SessionPhase.CONNECTING) return@launch
            val hangup = CallSignal(
                callId = callId,
                type = CallSignalType.HANGUP,
                timestampMs = System.currentTimeMillis(),
                reason = "ice_timeout"
            )
            teardownSessionLocked(
                endReasonText = "Αποτυχία σύνδεσης ICE",
                signalToSend = hangup,
                targetConvId = session.conversationId
            )
        }
    }

    private suspend fun teardownSessionLocked(
        endReasonText: String?,
        signalToSend: CallSignal?,
        targetConvId: String
    ) {
        val session = activeSession
        activeSession = null
        if (!endReasonText.isNullOrBlank()) {
            diagnosticsLogger("CALL", "Τερματισμός κλήσης: $endReasonText", false)
        }
        ringTimeoutJob?.cancel()
        connectTimeoutJob?.cancel()

        audioController.release()
        notificationManager.cancelAllCallNotifications()
        CallForegroundService.stop(appContext)

        if (session != null) {
            session.outboundSignalChannel.close()
            session.signalSenderJob?.cancel()
            webrtcEngine.disposeSessionAndAwait(session.callId)
        } else {
            webrtcEngine.disposeSessionAndAwait(null)
        }

        if (signalToSend != null) {
            sendSignalFireAndForget(targetConvId, signalToSend)
        }

        if (!endReasonText.isNullOrBlank() && session != null) {
            val callId = session.callId
            _callState.value = CallUiState.Ended(
                callId = callId,
                conversationId = session.conversationId,
                peerInboxId = session.peerInboxId,
                peerDisplayName = session.peerDisplayName,
                reasonText = endReasonText
            )
            endedDismissJob?.cancel()
            endedDismissJob = managerScope.launch {
                delay(2_200L)
                val current = _callState.value
                if (current is CallUiState.Ended && current.callId == callId) {
                    _callState.value = CallUiState.Idle
                }
            }
        } else {
            _callState.value = CallUiState.Idle
        }
    }

    private fun startSignalSenderLocked(session: ActiveSession) {
        session.signalSenderJob = ioScope.launch {
            for (signal in session.outboundSignalChannel) {
                val res = repository.sendCallSignal(session.conversationId, signal)
                if (res.isFailure && (signal.type == CallSignalType.OFFER || signal.type == CallSignalType.ANSWER)) {
                    managerScope.launch {
                        if (activeSession?.callId == session.callId) {
                            teardownSessionLocked(
                                endReasonText = "Αποτυχία αποστολής σήματος XMTP",
                                signalToSend = null,
                                targetConvId = session.conversationId
                            )
                        }
                    }
                    break
                }
            }
        }
    }

    private fun sendSignalFireAndForget(conversationId: String, signal: CallSignal) {
        ioScope.launch {
            withContext(NonCancellable) {
                repository.sendCallSignal(conversationId, signal)
            }
        }
    }

    private suspend fun resolveDmConversation(conversationId: String): Conversation.Dm? {
        val fromSnapshot = conversationsSnapshotProvider().firstOrNull { it.id == conversationId } as? Conversation.Dm
        if (fromSnapshot != null) return fromSnapshot
        return withContext(Dispatchers.IO) {
            runCatching {
                engine.client?.conversations?.findConversation(conversationId) as? Conversation.Dm
            }.getOrNull()
        }
    }

    private fun resolvePeerDisplayName(conversationId: String, peerInboxId: String): String {
        val snap = nicknameSnapshot.value
        val explicit = NicknamePolicy.resolveContactNickname(peerInboxId, snap)
            ?: NicknamePolicy.resolveContactNickname(conversationId, snap)
        if (!explicit.isNullOrBlank()) return explicit
        return if (peerInboxId.length > 14) {
            "${peerInboxId.take(6)}...${peerInboxId.takeLast(4)}"
        } else {
            peerInboxId
        }
    }

    private fun emergencyReset(throwable: Throwable) {
        Log.e(TAG, "Unhandled call coroutine failure", throwable)
        diagnosticsLogger(
            "CALL",
            "Μη αναμενόμενο σφάλμα κλήσης: ${throwable::class.java.simpleName}: ${throwable.message}",
            true
        )
        // Liberamos recursos de forma defensiva; cada paso aislado para que un fallo no impida los siguientes.
        runCatching { ringTimeoutJob?.cancel() }
        runCatching { connectTimeoutJob?.cancel() }
        runCatching { audioController.release() }
        runCatching { notificationManager.cancelAllCallNotifications() }
        runCatching { CallForegroundService.stop(appContext) }
        runCatching { webrtcEngine.disposeSession(null) }
        _callState.value = CallUiState.Idle
        runCatching {
            managerScope.launch {
                activeSession?.let { old ->
                    old.outboundSignalChannel.close()
                    old.signalSenderJob?.cancel()
                }
                activeSession = null
            }
        }
    }

    companion object {
        private const val TAG = "CallSessionManager"
    }
}
