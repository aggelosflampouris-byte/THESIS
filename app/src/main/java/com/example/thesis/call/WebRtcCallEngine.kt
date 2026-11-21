package com.example.thesis.call

import android.content.Context
import android.util.Log
import com.example.thesis.xmtp.CallIceCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

interface WebRtcCallListener {
    fun onLocalOfferCreated(callId: String, sdp: String)
    fun onLocalAnswerCreated(callId: String, sdp: String)
    fun onIceCandidateBatchReady(callId: String, candidates: List<CallIceCandidate>)
    fun onIceConnected(callId: String)
    fun onIceDisconnected(callId: String, reason: String)
    fun onTransportTypeChanged(callId: String, transportType: CallTransportType)
    fun onFingerprintsUpdated(callId: String, localFingerprint: String?, remoteFingerprint: String?)
    fun onEngineError(callId: String, reason: String)
}

// Motor WebRTC solo-voz con serializacion en hilo unico para evitar condiciones de carrera JNI al cerrar llamadas.
internal class WebRtcCallEngine(
    private val appContext: Context,
    private val listener: WebRtcCallListener,
    private val turnServersProvider: () -> List<IceEndpoint> = { emptyList() }
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val webrtcExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ThesisWebRtcThread").apply { isDaemon = true }
    }

    @Volatile
    private var activeCallId: String? = null

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var localFingerprint: String? = null
    private var remoteFingerprint: String? = null
    private var hasRemoteDescription: Boolean = false
    private val pendingRemoteIce = mutableListOf<CallIceCandidate>()
    private val outboundIceBuffer = mutableListOf<CallIceCandidate>()
    private var iceFlushJob: Job? = null

    fun startOutgoingOffer(callId: String) {
        webrtcExecutor.execute {
            if (!prepareSessionOnExecutor(callId)) return@execute
            val pc = peerConnection ?: return@execute
            val constraints = buildOfferAnswerConstraints()
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) {
                        listener.onEngineError(callId, "Empty local offer SDP")
                        return
                    }
                    setLocalSdpAndNotify(callId, sdp, isOffer = true)
                }

                override fun onSetSuccess() = Unit

                override fun onCreateFailure(error: String?) {
                    listener.onEngineError(callId, "createOffer failed: ${error ?: "unknown"}")
                }

                override fun onSetFailure(error: String?) = Unit
            }, constraints)
        }
    }

    fun acceptIncomingOfferAndCreateAnswer(
        callId: String,
        remoteOfferSdp: String,
        initialRemoteIce: List<CallIceCandidate> = emptyList()
    ) {
        webrtcExecutor.execute {
            if (!prepareSessionOnExecutor(callId)) return@execute
            val pc = peerConnection ?: return@execute
            if (initialRemoteIce.isNotEmpty()) {
                pendingRemoteIce.addAll(initialRemoteIce)
            }
            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    webrtcExecutor.execute {
                        if (activeCallId != callId) return@execute
                        hasRemoteDescription = true
                        remoteFingerprint = CallSignalingPolicy.extractDtlsSha256Fingerprint(remoteOfferSdp)
                        listener.onFingerprintsUpdated(callId, localFingerprint, remoteFingerprint)
                        drainPendingRemoteIceOnExecutor()
                        createLocalAnswerOnExecutor(callId)
                    }
                }

                override fun onCreateFailure(error: String?) = Unit

                override fun onSetFailure(error: String?) {
                    listener.onEngineError(callId, "setRemoteDescription(offer) failed: ${error ?: "unknown"}")
                }
            }, remoteDesc)
        }
    }

    fun applyRemoteAnswer(callId: String, remoteAnswerSdp: String) {
        webrtcExecutor.execute {
            if (activeCallId != callId) return@execute
            val pc = peerConnection ?: return@execute
            val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    webrtcExecutor.execute {
                        if (activeCallId != callId) return@execute
                        hasRemoteDescription = true
                        remoteFingerprint = CallSignalingPolicy.extractDtlsSha256Fingerprint(remoteAnswerSdp)
                        listener.onFingerprintsUpdated(callId, localFingerprint, remoteFingerprint)
                        drainPendingRemoteIceOnExecutor()
                    }
                }

                override fun onCreateFailure(error: String?) = Unit

                override fun onSetFailure(error: String?) {
                    listener.onEngineError(callId, "setRemoteDescription(answer) failed: ${error ?: "unknown"}")
                }
            }, remoteDesc)
        }
    }

    fun addRemoteIceCandidates(callId: String, candidates: List<CallIceCandidate>) {
        if (candidates.isEmpty()) return
        webrtcExecutor.execute {
            if (activeCallId != callId) return@execute
            if (!hasRemoteDescription || peerConnection == null) {
                pendingRemoteIce.addAll(candidates)
                return@execute
            }
            for (cand in candidates) {
                runCatching {
                    peerConnection?.addIceCandidate(
                        IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.candidate)
                    )
                }.onFailure { err ->
                    Log.w(TAG, "addIceCandidate ignored: ${err::class.java.simpleName}")
                }
            }
        }
    }

    fun setMicrophoneMuted(muted: Boolean) {
        webrtcExecutor.execute {
            runCatching { localAudioTrack?.setEnabled(!muted) }
            runCatching { audioDeviceModule?.setMicrophoneMute(muted) }
        }
    }

    fun disposeSession(callId: String? = null) {
        webrtcExecutor.execute {
            if (callId != null && activeCallId != null && activeCallId != callId) {
                return@execute
            }
            disposeSessionOnExecutor()
        }
    }

    suspend fun disposeSessionAndAwait(callId: String? = null) {
        val done = CompletableDeferred<Unit>()
        webrtcExecutor.execute {
            try {
                if (callId == null || activeCallId == null || activeCallId == callId) {
                    disposeSessionOnExecutor()
                }
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    fun shutdown() {
        disposeSession(null)
        iceFlushJob?.cancel()
        engineScope.cancel()
        webrtcExecutor.shutdown()
    }

    private fun prepareSessionOnExecutor(callId: String): Boolean {
        disposeSessionOnExecutor()
        ensureFactoryInitialized(appContext)

        activeCallId = callId
        localFingerprint = null
        remoteFingerprint = null
        hasRemoteDescription = false
        pendingRemoteIce.clear()
        outboundIceBuffer.clear()

        return try {
            val adm = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        Log.w(TAG, "AudioRecord init error: $errorMessage")
                    }

                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.w(TAG, "AudioRecord start error ($errorCode): $errorMessage")
                    }

                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        Log.w(TAG, "AudioRecord runtime error: $errorMessage")
                    }
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                        Log.w(TAG, "AudioTrack init error: $errorMessage")
                    }

                    override fun onWebRtcAudioTrackStartError(
                        errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.w(TAG, "AudioTrack start error ($errorCode): $errorMessage")
                    }

                    override fun onWebRtcAudioTrackError(errorMessage: String?) {
                        Log.w(TAG, "AudioTrack runtime error: $errorMessage")
                    }
                })
                .createAudioDeviceModule()
            audioDeviceModule = adm

            val factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
            peerConnectionFactory = factory

            val iceServers = IceServerConfig.buildIceServerList(turnServersProvider())
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val pc = factory.createPeerConnection(rtcConfig, createPeerObserver(callId))
            if (pc == null) {
                disposeSessionOnExecutor()
                listener.onEngineError(callId, "PeerConnectionFactory returned null")
                return false
            }
            peerConnection = pc

            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            val source = factory.createAudioSource(audioConstraints)
            audioSource = source

            val track = factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, source).apply {
                setEnabled(true)
            }
            localAudioTrack = track
            pc.addTrack(track, listOf(LOCAL_MEDIA_STREAM_ID))
            true
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC initialization failed: ${e::class.java.simpleName}", e)
            disposeSessionOnExecutor()
            listener.onEngineError(callId, "WebRTC init failed: ${e.message ?: e::class.java.simpleName}")
            false
        }
    }

    private fun createLocalAnswerOnExecutor(callId: String) {
        val pc = peerConnection ?: return
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    listener.onEngineError(callId, "Empty local answer SDP")
                    return
                }
                setLocalSdpAndNotify(callId, sdp, isOffer = false)
            }

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(error: String?) {
                listener.onEngineError(callId, "createAnswer failed: ${error ?: "unknown"}")
            }

            override fun onSetFailure(error: String?) = Unit
        }, buildOfferAnswerConstraints())
    }

    private fun setLocalSdpAndNotify(callId: String, sdp: SessionDescription, isOffer: Boolean) {
        webrtcExecutor.execute {
            if (activeCallId != callId) return@execute
            val pc = peerConnection ?: return@execute
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(ignored: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    webrtcExecutor.execute {
                        if (activeCallId != callId) return@execute
                        localFingerprint = CallSignalingPolicy.extractDtlsSha256Fingerprint(sdp.description)
                        listener.onFingerprintsUpdated(callId, localFingerprint, remoteFingerprint)
                        if (isOffer) {
                            listener.onLocalOfferCreated(callId, sdp.description)
                        } else {
                            listener.onLocalAnswerCreated(callId, sdp.description)
                        }
                    }
                }

                override fun onCreateFailure(error: String?) = Unit

                override fun onSetFailure(error: String?) {
                    listener.onEngineError(callId, "setLocalDescription failed: ${error ?: "unknown"}")
                }
            }, sdp)
        }
    }

    private fun drainPendingRemoteIceOnExecutor() {
        val pc = peerConnection ?: return
        if (pendingRemoteIce.isEmpty()) return
        val queued = pendingRemoteIce.toList()
        pendingRemoteIce.clear()
        for (cand in queued) {
            runCatching {
                pc.addIceCandidate(IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.candidate))
            }
        }
    }

    private fun createPeerObserver(callId: String): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            if (activeCallId != callId) return
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> listener.onIceConnected(callId)
                PeerConnection.IceConnectionState.FAILED -> listener.onIceDisconnected(callId, "ICE_FAILED")
                PeerConnection.IceConnectionState.CLOSED -> listener.onIceDisconnected(callId, "ICE_CLOSED")
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (activeCallId != callId) return
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> listener.onIceConnected(callId)
                PeerConnection.PeerConnectionState.FAILED -> listener.onIceDisconnected(callId, "PEER_FAILED")
                PeerConnection.PeerConnectionState.CLOSED -> listener.onIceDisconnected(callId, "PEER_CLOSED")
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                webrtcExecutor.execute {
                    flushOutboundIceOnExecutor(callId)
                }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            val wireCand = CallIceCandidate(
                sdpMid = candidate.sdpMid ?: "0",
                sdpMLineIndex = candidate.sdpMLineIndex,
                candidate = candidate.sdp ?: return
            )
            webrtcExecutor.execute {
                if (activeCallId != callId) return@execute
                outboundIceBuffer.add(wireCand)
                scheduleDebouncedIceFlush(callId)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
            if (activeCallId != callId || event == null) return
            val localType = CallSignalingPolicy.classifyIceCandidateType(event.local?.sdp)
            val remoteType = CallSignalingPolicy.classifyIceCandidateType(event.remote?.sdp)
            val resolved = when {
                localType == CallTransportType.RELAYED_TURN ||
                    remoteType == CallTransportType.RELAYED_TURN -> CallTransportType.RELAYED_TURN
                localType == CallTransportType.DIRECT_P2P ||
                    remoteType == CallTransportType.DIRECT_P2P -> CallTransportType.DIRECT_P2P
                else -> CallTransportType.UNKNOWN
            }
            if (resolved != CallTransportType.UNKNOWN) {
                listener.onTransportTypeChanged(callId, resolved)
            }
        }

        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(dataChannel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        override fun onTrack(transceiver: RtpTransceiver?) = Unit
    }

    private fun scheduleDebouncedIceFlush(callId: String) {
        if (iceFlushJob?.isActive == true) return
        // Agrupamos candidatos ICE durante 300 ms para no saturar la rafaga de mensajes MLS.
        iceFlushJob = engineScope.launch {
            delay(CallSignalingPolicy.ICE_BATCH_DEBOUNCE_MS)
            webrtcExecutor.execute {
                flushOutboundIceOnExecutor(callId)
            }
        }
    }

    private fun flushOutboundIceOnExecutor(callId: String) {
        iceFlushJob?.cancel()
        iceFlushJob = null
        if (activeCallId != callId || outboundIceBuffer.isEmpty()) return
        val batch = outboundIceBuffer.toList()
        outboundIceBuffer.clear()
        listener.onIceCandidateBatchReady(callId, batch)
    }

    private fun disposeSessionOnExecutor() {
        iceFlushJob?.cancel()
        iceFlushJob = null
        activeCallId = null
        hasRemoteDescription = false
        localFingerprint = null
        remoteFingerprint = null
        pendingRemoteIce.clear()
        outboundIceBuffer.clear()

        // Liberamos objetos nativos en orden inverso al de creacion para evitar fugas o bloqueos de audio.
        runCatching { localAudioTrack?.setEnabled(false) }
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null

        runCatching { audioSource?.dispose() }
        audioSource = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null

        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null

        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
    }

    private fun buildOfferAnswerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    companion object {
        private const val TAG = "WebRtcCallEngine"
        private const val LOCAL_AUDIO_TRACK_ID = "THESIS_VOICE_TRACK_0"
        private const val LOCAL_MEDIA_STREAM_ID = "THESIS_VOICE_STREAM"
        private val webrtcInitialized = AtomicBoolean(false)

        private fun ensureFactoryInitialized(appContext: Context) {
            if (webrtcInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(appContext.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }
    }
}
