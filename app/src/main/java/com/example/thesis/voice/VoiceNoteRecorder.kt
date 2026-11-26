package com.example.thesis.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.thesis.transfer.TransferFileHygiene
import com.example.thesis.xmtp.VoiceNote
import com.example.thesis.xmtp.VoiceNoteCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

data class VoiceRecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val livePeaks: List<Int> = emptyList()
)

// Grabadora AAC-LC local con archivo temporal que se tritura al instante tras leer los bytes cifrables a memoria.
internal class VoiceNoteRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(VoiceRecordingState())
    val state: StateFlow<VoiceRecordingState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var startedAtElapsedMs: Long = 0L
    private var pollJob: Job? = null
    private val capturedPeaks = mutableListOf<Int>()

    fun start(onAutoMaxDurationReached: (VoiceNote) -> Unit = {}): Result<Unit> = synchronized(lock) {
        if (recorder != null) {
            return@synchronized Result.failure(IllegalStateException("Recording already in progress"))
        }
        val tmpDir = File(appContext.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }
        val targetFile = File(tmpDir, "vn_${UUID.randomUUID()}.m4a")
        var createdRecorder: MediaRecorder? = null

        return@synchronized runCatching {
            createdRecorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(24_000)
                setMaxDuration(VoiceNoteCodec.MAX_DURATION_MS.toInt())
                setMaxFileSize(VoiceNoteCodec.MAX_AUDIO_BYTES.toLong())
                setOutputFile(targetFile.absolutePath)
                prepare()
                start()
            }
            recorder = createdRecorder
            tempFile = targetFile
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            capturedPeaks.clear()
            _state.value = VoiceRecordingState(isRecording = true, elapsedMs = 0L, livePeaks = emptyList())

            pollJob?.cancel()
            pollJob = scope.launch {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    val shouldAutoStop = synchronized(lock) {
                        val active = recorder ?: return@synchronized false
                        val elapsed = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
                        val normalized = normalizeAmplitude(runCatching { active.maxAmplitude }.getOrDefault(0))
                        capturedPeaks.add(normalized)
                        _state.value = VoiceRecordingState(
                            isRecording = true,
                            elapsedMs = elapsed,
                            livePeaks = capturedPeaks.takeLast(28)
                        )
                        elapsed >= VoiceNoteCodec.MAX_DURATION_MS
                    }
                    if (shouldAutoStop) {
                        stopAndBuildVoiceNote().onSuccess { autoNote ->
                            onAutoMaxDurationReached(autoNote)
                        }
                        break
                    }
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to start voice recording: ${err::class.java.simpleName}: ${err.message}")
            runCatching { createdRecorder?.reset() }
            runCatching { createdRecorder?.release() }
            recorder = null
            tempFile = null
            TransferFileHygiene.shredAndDelete(targetFile)
            _state.value = VoiceRecordingState()
        }
    }

    fun stopAndBuildVoiceNote(): Result<VoiceNote> = synchronized(lock) {
        pollJob?.cancel()
        pollJob = null
        val active = recorder
        val file = tempFile
        recorder = null
        tempFile = null

        val elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs)
            .coerceIn(0L, VoiceNoteCodec.MAX_DURATION_MS)
        val peaksSnapshot = capturedPeaks.toList()
        capturedPeaks.clear()
        _state.value = VoiceRecordingState()

        if (active == null || file == null) {
            TransferFileHygiene.shredAndDelete(file)
            return@synchronized Result.failure(IllegalStateException("Δεν υπάρχει ενεργή εγγραφή."))
        }

        return@synchronized try {
            runCatching { active.stop() }.onFailure {
                // MediaRecorder.stop falla si se detiene antes de capturar suficientes tramas de audio.
                throw IllegalStateException("Πολύ σύντομη εγγραφή φωνής.")
            }
            if (elapsedMs < MIN_DURATION_MS) {
                throw IllegalStateException("Πολύ σύντομη εγγραφή φωνής.")
            }
            val bytes = if (file.exists()) file.readBytes() else ByteArray(0)
            if (bytes.isEmpty()) {
                throw IllegalStateException("Κενό αρχείο ήχου.")
            }
            if (bytes.size > VoiceNoteCodec.MAX_AUDIO_BYTES) {
                throw IllegalStateException("Το φωνητικό μήνυμα υπερβαίνει το όριο των 512 KB.")
            }
            Result.success(
                VoiceNote(
                    durationMs = elapsedMs,
                    waveformPeaks = VoiceNoteCodec.downsampleWaveform(peaksSnapshot, 28),
                    audioBytes = bytes
                )
            )
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            runCatching { active.reset() }
            runCatching { active.release() }
            // Eliminamos el archivo temporal inmediatamente tras pasar los bytes a memoria RAM.
            TransferFileHygiene.shredAndDelete(file)
        }
    }

    fun cancel() {
        synchronized(lock) {
            pollJob?.cancel()
            pollJob = null
            val active = recorder
            val file = tempFile
            recorder = null
            tempFile = null
            capturedPeaks.clear()
            _state.value = VoiceRecordingState()
            runCatching { active?.stop() }
            runCatching { active?.reset() }
            runCatching { active?.release() }
            TransferFileHygiene.shredAndDelete(file)
        }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            // Necesario para compatibilidad con Android 8-11 previo a MediaRecorder(Context).
            MediaRecorder()
        }
    }

    private fun normalizeAmplitude(rawAmplitude: Int): Int {
        if (rawAmplitude <= 0) return 10
        val fraction = (rawAmplitude.toFloat() / 32_767f).coerceIn(0f, 1f)
        // Usamos raiz cuadrada para resaltar niveles conversacionales suaves en las barras visuales.
        return (10f + sqrt(fraction) * 90f).toInt().coerceIn(10, 100)
    }

    companion object {
        private const val TAG = "VoiceNoteRecorder"
        private const val TEMP_DIR_NAME = "voice_tmp"
        private const val POLL_INTERVAL_MS = 120L
        private const val MIN_DURATION_MS = 500L

        fun purgeTempDir(cacheDir: File) {
            val dir = File(cacheDir, TEMP_DIR_NAME)
            if (dir.exists()) {
                TransferFileHygiene.shredAndDelete(dir)
            }
        }
    }
}

