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
import org.webrtc.EglBase
import org.webrtc.VideoSink
import org.xmtp.android.library.Conversation
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

sealed interface CallUiState {
    val callId: String
    val conversationId: String
    val peerInboxId: String
    val peerDisplayName: String
    val isVideo: Boolean

    data object Idle : CallUiState {
        override val callId: String = ""
        override val conversationId: String = ""
        override val peerInboxId: String = ""
        override val peerDisplayName: String = ""
        override val isVideo: Boolean = false
    }

    data class OutgoingRinging(
        override val callId: String,
        override val conversationId: String,
        override val peerInboxId: String,
        override val peerDisplayName: String,
        override val isVideo: Boolean = false,
        val isCameraEnabled: Boolean = isVideo,
        val isFrontCamera: Boolean = true
    ) : CallUiState

    data class IncomingRinging(
        override val callId: String,
        override val conversationId: String,
        override val peerInboxId: String,
        override val peerDisplayName: String,
        override val isVideo: Boolean = false
    ) : CallUiState

    data class Connecting(
        override val callId: String,
        override val conversationId: String,
        val isOutgoing: Boolean,
        override val peerInboxId: String,
        override val peerDisplayName: String,
        val sasCode: String? = null,
        override val isVideo: Boolean = false,
        val isCameraEnabled: Boolean = false,
        val isFrontCamera: Boolean = true
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
        val transportType: CallTransportType = CallTransportType.UNKNOWN,
        override val isVideo: Boolean = false,
        val isCameraEnabled: Boolean = false,
        val isFrontCamera: Boolean = true,
        val hasRemoteVideo: Boolean = false
    ) : CallUiState

    data class Ended(
        override val callId: String,
        override val conversationId: String,
        override val peerInboxId: String,
        override val peerDisplayName: String,
        val reasonText: String,
        override val isVideo: Boolean = false
    ) : CallUiState
}

// Orquestador unico de estado para llamadas P2P de voz y video WebRTC + XMTP con cola FIFO y temporizadores.
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
        val isVideo: Boolean,
        var phase: SessionPhase,
        var remoteOfferSdp: String? = null,
        val bufferedRemoteIce: MutableList<CallIceCandidate> = mutableListOf(),
        var localFingerprint: String? = null,
        var remoteFingerprint: String? = null,
        var sasCode: String? = null,
        var transportType: CallTransportType = CallTransportType.UNKNOWN,
        var connectedAtElapsedMs: Long? = null,
        var isMuted: Boolean = false,
        var isSpeakerOn: Boolean = isVideo,
        var isCameraEnabled: Boolean = isVideo,
        var isFrontCamera: Boolean = true,
        var hasRemoteVideo: Boolean = false,
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

    @Volatile
    private var activeSession: ActiveSession? = null
    private var ringTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var endedDismissJob: Job? = null

    // Recordamos llamadas ya terminadas o rechazadas para que sincronizaciones posteriores nunca las vuelvan a abrir.
    private val terminatedCallIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(MAX_TERMINATED_CALL_IDS, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                    return size > MAX_TERMINATED_CALL_IDS
                }
            }
        )
    )

    init {
        managerScope.launch {
            repository.incomingCallSignals.collect { incoming ->
                handleIncomingSignalOnDispatcher(incoming)
            }
        }
    }

    fun getEglBaseContext(): EglBase.Context? = webrtcEngine.getOrCreateEglBaseContext()

    fun setLocalVideoSink(sink: VideoSink?) {
        webrtcEngine.setLocalVideoSink(sink)
    }

    fun setRemoteVideoSink(sink: VideoSink?) {
        webrtcEngine.setRemoteVideoSink(sink)
    }

    fun startOutgoingCall(conversation: Conversation, isVideo: Boolean = false) {
        if (conversation !is Conversation.Dm) return
        if (!CallForegroundService.hasRecordAudioPermission(appContext)) return
        val canUseCamera = isVideo && CallForegroundService.hasCameraPermission(appContext)
        if (isVideo && !canUseCamera) {
            diagnosticsLogger("CALL", "Αδυναμία βιντεοκλήσης: λείπει η άδεια CAMERA", true)
            return
        }

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
                isVideo = isVideo,
                phase = SessionPhase.OUTGOING_RINGING,
                isSpeakerOn = isVideo,
                isCameraEnabled = canUseCamera
            )
            startSignalSenderLocked(session)
            activeSession = session
            _callState.value = CallUiState.OutgoingRinging(
                callId = callId,
                conversationId = conversation.id,
                peerInboxId = peerInboxId,
                peerDisplayName = peerName,
                isVideo = isVideo,
                isCameraEnabled = canUseCamera,
                isFrontCamera = session.isFrontCamera
            )

            CallForegroundService.start(
                context = appContext,
                callId = callId,
                conversationId = conversation.id,
                peerName = peerName,
                statusLabel = if (isVideo) "Βιντεοκλήση σε εξέλιξη..." else "Κλήση σε εξέλιξη...",
                isVideo = isVideo
            )
            audioController.startInCallAudio(speakerOn = session.isSpeakerOn)
            scheduleRingTimeoutLocked(callId, isOutgoing = true)
            diagnosticsLogger(
                "CALL",
                if (isVideo) "Εξερχόμενη βιντεοκλήση [call=${callId.take(8)}]" else "Εξερχόμενη κλήση [call=${callId.take(8)}]",
                false
            )
            webrtcEngine.startOutgoingOffer(callId, isVideo = isVideo)
        }
    }

    fun acceptIncomingCall(targetCallId: String? = null) {
        if (!CallForegroundService.hasRecordAudioPermission(appContext)) {
            diagnosticsLogger("CALL", "Αδυναμία απάντησης: λείπει η άδεια RECORD_AUDIO", true)
            return
        }
        // Detenemos timbre y notificacion al instante y adelantamos el estado visual para respuesta inmediata al toque.
        audioController.stopRinging()
        notificationManager.cancelIncomingCallNotification()
        val currentUi = _callState.value
        val canUseCamera = currentUi.isVideo && CallForegroundService.hasCameraPermission(appContext)
        if (currentUi is CallUiState.IncomingRinging &&
            (targetCallId.isNullOrBlank() || currentUi.callId == targetCallId)
        ) {
            _callState.value = CallUiState.Connecting(
                callId = currentUi.callId,
                conversationId = currentUi.conversationId,
                isOutgoing = false,
                peerInboxId = currentUi.peerInboxId,
                peerDisplayName = currentUi.peerDisplayName,
                sasCode = null,
                isVideo = currentUi.isVideo,
                isCameraEnabled = canUseCamera,
                isFrontCamera = true
            )
        }

        managerScope.launch {
            val session = activeSession
            if (session == null) {
                _callState.value = CallUiState.Idle
                return@launch
            }
            if (!targetCallId.isNullOrBlank() && session.callId != targetCallId) return@launch
            // Evitamos reiniciar PeerConnection/DTLS si el usuario toca Aceptar dos veces seguidas.
            if (session.phase != SessionPhase.INCOMING_RINGING) {
                return@launch
            }
            val offerSdp = session.remoteOfferSdp
            if (offerSdp.isNullOrBlank()) {
                teardownSessionLocked(
                    endReasonText = "Μη έγκυρο σήμα κλήσης",
                    signalToSend = null,
                    targetConvId = session.conversationId
                )
                return@launch
            }

            ringTimeoutJob?.cancel()
            val enableCamera = session.isVideo && CallForegroundService.hasCameraPermission(appContext)
            session.phase = SessionPhase.CONNECTING
            session.isCameraEnabled = enableCamera
            session.isSpeakerOn = session.isVideo
            _callState.value = CallUiState.Connecting(
                callId = session.callId,
                conversationId = session.conversationId,
                isOutgoing = false,
                peerInboxId = session.peerInboxId,
                peerDisplayName = session.peerDisplayName,
                sasCode = session.sasCode,
                isVideo = session.isVideo,
                isCameraEnabled = enableCamera,
                isFrontCamera = session.isFrontCamera
            )

            diagnosticsLogger(
                "CALL",
                if (session.isVideo) "Αποδοχή βιντεοκλήσης [call=${session.callId.take(8)}]" else "Αποδοχή εισερχόμενης κλήσης [call=${session.callId.take(8)}]",
                false
            )
            CallForegroundService.start(
                context = appContext,
                callId = session.callId,
                conversationId = session.conversationId,
                peerName = session.peerDisplayName,
                statusLabel = if (session.isVideo) "Σύνδεση βιντεοκλήσης..." else "Σύνδεση κλήσης...",
                isVideo = enableCamera
            )
            audioController.startInCallAudio(speakerOn = session.isSpeakerOn)
            scheduleConnectTimeoutLocked(session.callId)

            val initialIce = session.bufferedRemoteIce.toList()
            session.bufferedRemoteIce.clear()
            webrtcEngine.acceptIncomingOfferAndCreateAnswer(
                callId = session.callId,
                remoteOfferSdp = offerSdp,
                initialRemoteIce = initialIce,
                isVideo = session.isVideo,
                enableLocalCamera = enableCamera
            )
        }
    }

    fun declineIncomingCall(targetCallId: String? = null) {
        val currentUi = _callState.value
        if (!targetCallId.isNullOrBlank() && currentUi.callId.isNotBlank() && currentUi.callId != targetCallId) {
            return
        }
        if (currentUi.callId.isNotBlank()) {
            terminatedCallIds.add(currentUi.callId)
        }
        // Cerramos la superposicion de inmediato en el hilo llamador para que el boton Rechazar nunca quede bloqueado.
        audioController.stopRinging()
        notificationManager.cancelAllCallNotifications()
        _callState.value = CallUiState.Idle

        managerScope.launch {
            val session = activeSession ?: run {
                CallForegroundService.stop(appContext)
                audioController.release()
                _callState.value = CallUiState.Idle
                return@launch
            }
            if (!targetCallId.isNullOrBlank() && session.callId != targetCallId) return@launch
            terminatedCallIds.add(session.callId)
            diagnosticsLogger("CALL", "Απόρριψη εισερχόμενης κλήσης [call=${session.callId.take(8)}]", false)

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
        val currentUi = _callState.value
        if (!targetCallId.isNullOrBlank() && currentUi.callId.isNotBlank() && currentUi.callId != targetCallId) {
            return
        }
        if (currentUi.callId.isNotBlank()) {
            terminatedCallIds.add(currentUi.callId)
        }
        audioController.stopRinging()
        notificationManager.cancelAllCallNotifications()
        _callState.value = CallUiState.Idle

        managerScope.launch {
            val session = activeSession ?: run {
                CallForegroundService.stop(appContext)
                audioController.release()
                _callState.value = CallUiState.Idle
                return@launch
            }
            if (!targetCallId.isNullOrBlank() && session.callId != targetCallId) return@launch
            terminatedCallIds.add(session.callId)

            val signalType = if (session.phase == SessionPhase.INCOMING_RINGING) {
                CallSignalType.REJECT
            } else {
                CallSignalType.HANGUP
            }
            val durationSec = session.connectedAtElapsedMs?.let { connectedAt ->
                ((SystemClock.elapsedRealtime() - connectedAt) / 1_000L).coerceAtLeast(1L)
            }
            val reason = if (signalType == CallSignalType.HANGUP) {
                CallHistoryPolicy.encodeHangupReason("hangup", durationSec)
            } else {
                "declined"
            }
            val signal = CallSignal(
                callId = session.callId,
                type = signalType,
                timestampMs = System.currentTimeMillis(),
                reason = reason
            )
            teardownSessionLocked(
                endReasonText = null,
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

    fun toggleCamera() {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (!session.isVideo) return@launch
            if (!session.isCameraEnabled && !CallForegroundService.hasCameraPermission(appContext)) return@launch
            val nextEnabled = !session.isCameraEnabled
            session.isCameraEnabled = nextEnabled
            webrtcEngine.setVideoEnabled(nextEnabled)
            refreshVideoFlagsOnState(session)
        }
    }

    fun switchCamera() {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (!session.isVideo || !session.isCameraEnabled) return@launch
            webrtcEngine.switchCamera()
        }
    }

    fun dismissEndedState() {
        endedDismissJob?.cancel()
        if (_callState.value is CallUiState.Ended) {
            _callState.value = CallUiState.Idle
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
                terminatedCallIds.add(old.callId)
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
                    sdp = sdp,
                    reason = if (session.isVideo) "video" else null
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
                transportType = session.transportType,
                isVideo = session.isVideo,
                isCameraEnabled = session.isCameraEnabled,
                isFrontCamera = session.isFrontCamera,
                hasRemoteVideo = session.hasRemoteVideo
            )
            notificationManager.updateOngoingCallNotification(
                callId = session.callId,
                conversationId = session.conversationId,
                peerName = session.peerDisplayName,
                statusLabel = if (session.isVideo) {
                    "Βιντεοκλήση σε εξέλιξη (DTLS-SRTP)"
                } else {
                    "Κλήση φωνής σε εξέλιξη (DTLS-SRTP)"
                }
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

    override fun onRemoteVideoAvailable(callId: String, available: Boolean) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            session.hasRemoteVideo = available
            refreshVideoFlagsOnState(session)
        }
    }

    override fun onCameraFacingChanged(callId: String, isFrontFacing: Boolean) {
        managerScope.launch {
            val session = activeSession ?: return@launch
            if (session.callId != callId) return@launch
            session.isFrontCamera = isFrontFacing
            refreshVideoFlagsOnState(session)
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
                endReasonText = "Σφάλμα ήχου/βίντεο WebRTC",
                signalToSend = hangupSignal,
                targetConvId = session.conversationId
            )
        }
    }

    override fun onCallDiagnostic(callId: String, message: String) {
        diagnosticsLogger("CALL", "[call=${callId.take(8)}] $message", false)
    }

    private fun refreshVideoFlagsOnState(session: ActiveSession) {
        when (val current = _callState.value) {
            is CallUiState.OutgoingRinging -> if (current.callId == session.callId) {
                _callState.value = current.copy(
                    isCameraEnabled = session.isCameraEnabled,
                    isFrontCamera = session.isFrontCamera
                )
            }
            is CallUiState.Connecting -> if (current.callId == session.callId) {
                _callState.value = current.copy(
                    isCameraEnabled = session.isCameraEnabled,
                    isFrontCamera = session.isFrontCamera
                )
            }
            is CallUiState.Active -> if (current.callId == session.callId) {
                _callState.value = current.copy(
                    isCameraEnabled = session.isCameraEnabled,
                    isFrontCamera = session.isFrontCamera,
                    hasRemoteVideo = session.hasRemoteVideo
                )
            }
            else -> Unit
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
        if (signal.callId.isBlank() || signal.callId in terminatedCallIds) {
            return
        }
        val offerSdp = signal.sdp ?: return
        val offerTs = signal.timestampMs.takeIf { it > 0L } ?: incoming.sentAtMs
        if (CallSignalingPolicy.isOfferExpired(offerTimestampMs = offerTs)) {
            terminatedCallIds.add(signal.callId)
            Log.i(TAG, "Ignoring expired OFFER for callId=${signal.callId}")
            return
        }

        val conversation = resolveDmConversation(incoming.conversationId) ?: return
        val localInboxId = engine.client?.inboxId.orEmpty()
        val current = activeSession

        if (current != null) {
            if (current.callId == signal.callId) return
            val isSamePeer = current.peerInboxId.equals(incoming.senderInboxId, ignoreCase = true)
            // Si el mismo contacto envia una nueva oferta mientras la anterior aun estaba timbrando sin responder,
            // reemplazamos la oferta obsoleta en lugar de responder BUSY a su reintento.
            if (isSamePeer && current.phase == SessionPhase.INCOMING_RINGING) {
                terminatedCallIds.add(current.callId)
                teardownSessionLocked(
                    endReasonText = null,
                    signalToSend = null,
                    targetConvId = current.conversationId
                )
            } else if (isSamePeer && current.phase == SessionPhase.OUTGOING_RINGING) {
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
                terminatedCallIds.add(signal.callId)
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
        val isVideo = CallSignalingPolicy.isVideoOffer(offerSdp, signal.reason)
        val peerName = resolvePeerDisplayName(conversation.id, incoming.senderInboxId)
        val session = ActiveSession(
            callId = signal.callId,
            conversationId = conversation.id,
            peerInboxId = incoming.senderInboxId,
            peerDisplayName = peerName,
            isOutgoing = false,
            isVideo = isVideo,
            phase = SessionPhase.INCOMING_RINGING,
            remoteOfferSdp = offerSdp,
            remoteFingerprint = CallSignalingPolicy.extractDtlsSha256Fingerprint(offerSdp),
            isSpeakerOn = isVideo,
            isCameraEnabled = isVideo
        )
        startSignalSenderLocked(session)
        activeSession = session
        _callState.value = CallUiState.IncomingRinging(
            callId = session.callId,
            conversationId = session.conversationId,
            peerInboxId = session.peerInboxId,
            peerDisplayName = session.peerDisplayName,
            isVideo = isVideo
        )

        diagnosticsLogger(
            "CALL",
            if (isVideo) "Εισερχόμενη βιντεοκλήση [call=${session.callId.take(8)}]" else "Εισερχόμενη κλήση [call=${session.callId.take(8)}]",
            false
        )
        audioController.startIncomingRinging()
        notificationManager.showIncomingCallNotification(
            callId = session.callId,
            conversationId = session.conversationId,
            callerName = session.peerDisplayName,
            isVideo = isVideo
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
            sasCode = session.sasCode,
            isVideo = session.isVideo,
            isCameraEnabled = session.isCameraEnabled,
            isFrontCamera = session.isFrontCamera
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
        if (incoming.signal.callId.isNotBlank()) {
            terminatedCallIds.add(incoming.signal.callId)
        }
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
                endReasonText = "Αδυναμία απευθείας P2P (περιορισμένο NAT)",
                signalToSend = hangup,
                targetConvId = session.conversationId
            )
        }
    }

    private fun teardownSessionLocked(
        endReasonText: String?,
        signalToSend: CallSignal?,
        targetConvId: String
    ) {
        val session = activeSession
        activeSession = null
        if (session != null) {
            terminatedCallIds.add(session.callId)
        }
        ringTimeoutJob?.cancel()
        connectTimeoutJob?.cancel()

        // Actualizamos primero el estado de UI para que la pantalla nunca quede bloqueada esperando cierre de recursos.
        if (!endReasonText.isNullOrBlank() && session != null) {
            val callId = session.callId
            _callState.value = CallUiState.Ended(
                callId = callId,
                conversationId = session.conversationId,
                peerInboxId = session.peerInboxId,
                peerDisplayName = session.peerDisplayName,
                reasonText = endReasonText,
                isVideo = session.isVideo
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

        if (!endReasonText.isNullOrBlank()) {
            diagnosticsLogger("CALL", "Τερματισμός κλήσης: $endReasonText", false)
        }

        audioController.release()
        notificationManager.cancelAllCallNotifications()
        CallForegroundService.stop(appContext)

        if (session != null) {
            session.outboundSignalChannel.close()
            session.signalSenderJob?.cancel()
            webrtcEngine.disposeSession(session.callId)
        } else {
            webrtcEngine.disposeSession(null)
        }

        if (signalToSend != null) {
            sendSignalFireAndForget(targetConvId, signalToSend)
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
        _callState.value = CallUiState.Idle
        runCatching { ringTimeoutJob?.cancel() }
        runCatching { connectTimeoutJob?.cancel() }
        runCatching { audioController.release() }
        runCatching { notificationManager.cancelAllCallNotifications() }
        runCatching { CallForegroundService.stop(appContext) }
        runCatching { webrtcEngine.disposeSession(null) }
        runCatching {
            managerScope.launch {
                activeSession?.let { old ->
                    terminatedCallIds.add(old.callId)
                    old.outboundSignalChannel.close()
                    old.signalSenderJob?.cancel()
                }
                activeSession = null
            }
        }
    }

    companion object {
        private const val TAG = "CallSessionManager"
        private const val MAX_TERMINATED_CALL_IDS = 256
    }
}
