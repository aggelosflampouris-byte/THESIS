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
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

interface WebRtcCallListener {
    fun onLocalOfferCreated(callId: String, sdp: String)
    fun onLocalAnswerCreated(callId: String, sdp: String)
    fun onIceCandidateBatchReady(callId: String, candidates: List<CallIceCandidate>)
    fun onIceConnected(callId: String)
    fun onIceDisconnected(callId: String, reason: String)
    fun onTransportTypeChanged(callId: String, transportType: CallTransportType)
    fun onFingerprintsUpdated(callId: String, localFingerprint: String?, remoteFingerprint: String?)
    fun onEngineError(callId: String, reason: String)
    fun onCallDiagnostic(callId: String, message: String) = Unit
    fun onRemoteVideoAvailable(callId: String, available: Boolean) = Unit
    fun onCameraFacingChanged(callId: String, isFrontFacing: Boolean) = Unit
}

// Motor WebRTC de voz y video P2P con serializacion en hilo unico para evitar condiciones de carrera JNI y EGL.
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

    @Volatile
    private var sharedEglBase: EglBase? = null
    private val eglLock = Any()

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localVideoSink: VideoSink? = null
    private var remoteVideoSink: VideoSink? = null
    private var isVideoSession: Boolean = false
    private var isFrontCamera: Boolean = true
    private var isCapturing: Boolean = false

    private var localFingerprint: String? = null
    private var remoteFingerprint: String? = null
    private var hasRemoteDescription: Boolean = false
    private val pendingRemoteIce = mutableListOf<CallIceCandidate>()
    private val outboundIceBuffer = mutableListOf<CallIceCandidate>()
    private var iceFlushJob: Job? = null

    // Mantenemos un EglBase compartido para evitar fallos si Compose desmonta SurfaceViewRenderer tras finalizar la llamada.
    fun getOrCreateEglBaseContext(): EglBase.Context? {
        synchronized(eglLock) {
            if (sharedEglBase == null) {
                sharedEglBase = runCatching { EglBase.create() }.getOrNull()
            }
            return sharedEglBase?.eglBaseContext
        }
    }

    fun startOutgoingOffer(callId: String, isVideo: Boolean = false) {
        runOnWebRtc {
            if (!prepareSessionOnExecutor(callId, isVideo = isVideo, enableLocalCamera = isVideo)) {
                return@runOnWebRtc
            }
            val pc = peerConnection ?: return@runOnWebRtc
            val constraints = buildOfferAnswerConstraints(isVideo = isVideo)
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
        initialRemoteIce: List<CallIceCandidate> = emptyList(),
        isVideo: Boolean = false,
        enableLocalCamera: Boolean = isVideo
    ) {
        runOnWebRtc {
            if (!prepareSessionOnExecutor(callId, isVideo = isVideo, enableLocalCamera = enableLocalCamera)) {
                return@runOnWebRtc
            }
            val pc = peerConnection ?: return@runOnWebRtc
            if (initialRemoteIce.isNotEmpty()) {
                pendingRemoteIce.addAll(initialRemoteIce)
            }
            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    runOnWebRtc {
                        if (activeCallId != callId) return@runOnWebRtc
                        hasRemoteDescription = true
                        remoteFingerprint = CallSignalingPolicy.extractDtlsSha256Fingerprint(remoteOfferSdp)
                        listener.onFingerprintsUpdated(callId, localFingerprint, remoteFingerprint)
                        drainPendingRemoteIceOnExecutor()
                        createLocalAnswerOnExecutor(callId, isVideo = isVideo)
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
        runOnWebRtc {
            if (activeCallId != callId) return@runOnWebRtc
            val pc = peerConnection ?: return@runOnWebRtc
            val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    runOnWebRtc {
                        if (activeCallId != callId) return@runOnWebRtc
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
        runOnWebRtc {
            if (activeCallId != callId) return@runOnWebRtc
            if (!hasRemoteDescription || peerConnection == null) {
                pendingRemoteIce.addAll(candidates)
                return@runOnWebRtc
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
        runOnWebRtc {
            runCatching { localAudioTrack?.setEnabled(!muted) }
            runCatching { audioDeviceModule?.setMicrophoneMute(muted) }
        }
    }

    fun setVideoEnabled(enabled: Boolean) {
        runOnWebRtc {
            runCatching { localVideoTrack?.setEnabled(enabled) }
            val capturer = videoCapturer ?: return@runOnWebRtc
            if (enabled && !isCapturing) {
                runCatching {
                    capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
                    isCapturing = true
                }
            } else if (!enabled && isCapturing) {
                runCatching {
                    capturer.stopCapture()
                    isCapturing = false
                }
            }
        }
    }

    fun switchCamera() {
        runOnWebRtc {
            val camCapturer = videoCapturer as? CameraVideoCapturer ?: return@runOnWebRtc
            val callId = activeCallId ?: return@runOnWebRtc
            camCapturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                    runOnWebRtc {
                        if (activeCallId != callId) return@runOnWebRtc
                        isFrontCamera = isFrontFacing
                        listener.onCameraFacingChanged(callId, isFrontFacing)
                    }
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.w(TAG, "Camera switch failed: $errorDescription")
                }
            })
        }
    }

    fun setLocalVideoSink(sink: VideoSink?) {
        runOnWebRtc {
            val previous = localVideoSink
            if (previous === sink) return@runOnWebRtc
            if (previous != null) {
                runCatching { localVideoTrack?.removeSink(previous) }
            }
            localVideoSink = sink
            if (sink != null) {
                runCatching { localVideoTrack?.addSink(sink) }
            }
        }
    }

    fun setRemoteVideoSink(sink: VideoSink?) {
        runOnWebRtc {
            val previous = remoteVideoSink
            if (previous === sink) return@runOnWebRtc
            if (previous != null) {
                runCatching { remoteVideoTrack?.removeSink(previous) }
            }
            remoteVideoSink = sink
            if (sink != null) {
                runCatching { remoteVideoTrack?.addSink(sink) }
            }
        }
    }

    fun disposeSession(callId: String? = null) {
        runOnWebRtc {
            if (callId != null && activeCallId != null && activeCallId != callId) {
                return@runOnWebRtc
            }
            disposeSessionOnExecutor()
        }
    }

    suspend fun disposeSessionAndAwait(callId: String? = null) {
        val done = CompletableDeferred<Unit>()
        runOnWebRtc {
            try {
                if (callId == null || activeCallId == null || activeCallId == callId) {
                    disposeSessionOnExecutor()
                }
            } finally {
                done.complete(Unit)
            }
        }
        withTimeoutOrNull(3_000L) { done.await() }
    }

    fun shutdown() {
        disposeSession(null)
        iceFlushJob?.cancel()
        engineScope.cancel()
        webrtcExecutor.shutdown()
        synchronized(eglLock) {
            runCatching { sharedEglBase?.release() }
            sharedEglBase = null
        }
    }

    // Capturamos cualquier Throwable (incluido UnsatisfiedLinkError) para que un fallo nativo no cierre el proceso.
    private fun runOnWebRtc(block: () -> Unit) {
        try {
            webrtcExecutor.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(TAG, "WebRTC task failed: ${t::class.java.simpleName}", t)
                    val failedCallId = activeCallId
                    runCatching { disposeSessionOnExecutor() }
                    if (failedCallId != null) {
                        listener.onEngineError(failedCallId, "${t::class.java.simpleName}: ${t.message ?: "unknown"}")
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "WebRTC executor rejected task after shutdown")
        }
    }

    private fun prepareSessionOnExecutor(
        callId: String,
        isVideo: Boolean,
        enableLocalCamera: Boolean
    ): Boolean {
        disposeSessionOnExecutor()

        activeCallId = callId
        isVideoSession = isVideo
        isFrontCamera = true
        isCapturing = false
        localFingerprint = null
        remoteFingerprint = null
        hasRemoteDescription = false
        pendingRemoteIce.clear()
        outboundIceBuffer.clear()

        return try {
            ensureFactoryInitialized(appContext)
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

            val eglContext = if (isVideo) getOrCreateEglBaseContext() else null
            val factoryBuilder = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
            if (isVideo && eglContext != null) {
                factoryBuilder
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            }
            val factory = factoryBuilder.createPeerConnectionFactory()
            peerConnectionFactory = factory

            val iceServers = IceServerConfig.buildIceServerList(turnServersProvider())
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceCandidatePoolSize = 2
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

            if (isVideo && enableLocalCamera && eglContext != null && CallForegroundService.hasCameraPermission(appContext)) {
                setupLocalCameraTrackOnExecutor(callId, factory, pc, eglContext)
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "WebRTC initialization failed: ${e::class.java.simpleName}", e)
            disposeSessionOnExecutor()
            listener.onEngineError(callId, "WebRTC init failed: ${e.message ?: e::class.java.simpleName}")
            false
        }
    }

    private fun setupLocalCameraTrackOnExecutor(
        callId: String,
        factory: PeerConnectionFactory,
        pc: PeerConnection,
        eglContext: EglBase.Context
    ) {
        // Si la camara falla al abrirse, continuamos con el audio y video remoto sin cerrar la llamada.
        runCatching {
            val enumerator = if (Camera2Enumerator.isSupported(appContext)) {
                Camera2Enumerator(appContext)
            } else {
                Camera1Enumerator(true)
            }
            val devices = enumerator.deviceNames
            val frontDevice = devices.firstOrNull { enumerator.isFrontFacing(it) }
            val chosenDevice = frontDevice ?: devices.firstOrNull()
                ?: throw IllegalStateException("No camera hardware available")

            isFrontCamera = enumerator.isFrontFacing(chosenDevice)
            listener.onCameraFacingChanged(callId, isFrontCamera)

            val capturer = enumerator.createCapturer(chosenDevice, null)
                ?: throw IllegalStateException("createCapturer returned null")
            videoCapturer = capturer

            val texHelper = SurfaceTextureHelper.create("ThesisVideoCaptureThread", eglContext)
            surfaceTextureHelper = texHelper

            val vSource = factory.createVideoSource(capturer.isScreencast)
            videoSource = vSource

            capturer.initialize(texHelper, appContext, vSource.capturerObserver)
            capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            isCapturing = true

            val vTrack = factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, vSource).apply {
                setEnabled(true)
            }
            localVideoTrack = vTrack
            localVideoSink?.let { sink ->
                runCatching { vTrack.addSink(sink) }
            }
            pc.addTrack(vTrack, listOf(LOCAL_MEDIA_STREAM_ID))
            listener.onCallDiagnostic(callId, "Local camera started ($chosenDevice)")
        }.onFailure { err ->
            Log.w(TAG, "Local camera setup failed: ${err::class.java.simpleName}: ${err.message}")
            listener.onCallDiagnostic(callId, "Camera fallback to audio-only: ${err.message}")
            releaseLocalVideoObjectsOnExecutor()
        }
    }

    private fun createLocalAnswerOnExecutor(callId: String, isVideo: Boolean) {
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
        }, buildOfferAnswerConstraints(isVideo = isVideo))
    }

    private fun setLocalSdpAndNotify(callId: String, sdp: SessionDescription, isOffer: Boolean) {
        runOnWebRtc {
            if (activeCallId != callId) return@runOnWebRtc
            val pc = peerConnection ?: return@runOnWebRtc
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(ignored: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    runOnWebRtc {
                        if (activeCallId != callId) return@runOnWebRtc
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
            listener.onCallDiagnostic(callId, "ICE state -> ${newState?.name}")
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
            listener.onCallDiagnostic(callId, "Peer/DTLS state -> ${newState?.name}")
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
                runOnWebRtc {
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
            runOnWebRtc {
                if (activeCallId != callId) return@runOnWebRtc
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
                listener.onCallDiagnostic(callId, "Selected transport -> ${resolved.name}")
                listener.onTransportTypeChanged(callId, resolved)
            }
        }

        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(dataChannel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            runOnWebRtc {
                if (activeCallId != callId) return@runOnWebRtc
                remoteVideoSink?.let { sink ->
                    runCatching { remoteVideoTrack?.removeSink(sink) }
                }
                remoteVideoTrack = track
                runCatching { track.setEnabled(true) }
                remoteVideoSink?.let { sink ->
                    runCatching { track.addSink(sink) }
                }
                listener.onRemoteVideoAvailable(callId, true)
                listener.onCallDiagnostic(callId, "Remote video track attached")
            }
        }
    }

    private fun scheduleDebouncedIceFlush(callId: String) {
        if (iceFlushJob?.isActive == true) return
        // Agrupamos candidatos ICE durante 300 ms para no saturar la rafaga de mensajes MLS.
        iceFlushJob = engineScope.launch {
            delay(CallSignalingPolicy.ICE_BATCH_DEBOUNCE_MS)
            runOnWebRtc {
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
        val hostCount = batch.count { it.candidate.contains(" typ host", ignoreCase = true) }
        val srflxCount = batch.count { it.candidate.contains(" typ srflx", ignoreCase = true) }
        val relayCount = batch.count {
            CallSignalingPolicy.classifyIceCandidateType(it.candidate) == CallTransportType.RELAYED_TURN
        }
        listener.onCallDiagnostic(
            callId,
            "ICE batch sent: ${batch.size} (host=$hostCount, srflx=$srflxCount, relay=$relayCount)"
        )
        listener.onIceCandidateBatchReady(callId, batch)
    }

    private fun releaseLocalVideoObjectsOnExecutor() {
        localVideoSink?.let { sink ->
            runCatching { localVideoTrack?.removeSink(sink) }
        }
        runCatching { localVideoTrack?.setEnabled(false) }
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null

        if (isCapturing) {
            runCatching { videoCapturer?.stopCapture() }
            isCapturing = false
        }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
    }

    private fun disposeSessionOnExecutor() {
        iceFlushJob?.cancel()
        iceFlushJob = null
        activeCallId = null
        hasRemoteDescription = false
        isVideoSession = false
        isFrontCamera = true
        localFingerprint = null
        remoteFingerprint = null
        pendingRemoteIce.clear()
        outboundIceBuffer.clear()

        remoteVideoSink?.let { sink ->
            runCatching { remoteVideoTrack?.removeSink(sink) }
        }
        remoteVideoTrack = null

        // Liberamos objetos nativos en orden inverso al de creacion antes de destruir PeerConnection y Factory.
        releaseLocalVideoObjectsOnExecutor()

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

    private fun buildOfferAnswerConstraints(isVideo: Boolean): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
    }

    companion object {
        private const val TAG = "WebRtcCallEngine"
        private const val LOCAL_AUDIO_TRACK_ID = "THESIS_VOICE_TRACK_0"
        private const val LOCAL_VIDEO_TRACK_ID = "THESIS_VIDEO_TRACK_0"
        private const val LOCAL_MEDIA_STREAM_ID = "THESIS_VOICE_STREAM"
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 480
        private const val VIDEO_FPS = 24
        private val initLock = Any()

        @Volatile
        private var webrtcInitialized = false

        // Marcamos la inicializacion solo tras exito para poder reintentar si la carga nativa falla.
        private fun ensureFactoryInitialized(appContext: Context) {
            if (webrtcInitialized) return
            synchronized(initLock) {
                if (webrtcInitialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(appContext.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                webrtcInitialized = true
            }
        }
    }
}
