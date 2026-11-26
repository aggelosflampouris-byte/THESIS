package com.example.thesis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Log
import com.example.thesis.xmtp.VoiceNote
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

data class VoicePlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

// Reproductor que decodifica el audio exclusivamente desde RAM mediante MediaDataSource sin escribir archivos en disco.
internal class VoiceNotePlayer(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    fun togglePlayPause(messageId: String, voiceNote: VoiceNote) {
        synchronized(lock) {
            val current = _state.value
            val player = mediaPlayer
            if (current.activeMessageId == messageId && player != null) {
                if (current.isPlaying) {
                    runCatching { player.pause() }
                    progressJob?.cancel()
                    progressJob = null
                    _state.value = current.copy(
                        isPlaying = false,
                        positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(current.positionMs)
                    )
                } else {
                    runCatching { player.start() }
                    _state.value = current.copy(isPlaying = true)
                    startProgressTickerLocked(messageId)
                }
                return
            }

            releasePlayerLocked()
            val newPlayer = MediaPlayer()
            val result = runCatching {
                newPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                newPlayer.setDataSource(ByteArrayMediaDataSource(voiceNote.audioBytes))
                newPlayer.prepare()
                val actualDuration = newPlayer.duration.toLong().takeIf { it > 0L } ?: voiceNote.durationMs
                newPlayer.setOnCompletionListener {
                    synchronized(lock) {
                        if (mediaPlayer === newPlayer) {
                            releasePlayerLocked()
                            _state.value = VoicePlaybackState()
                        }
                    }
                }
                newPlayer.setOnErrorListener { _, _, _ ->
                    synchronized(lock) {
                        if (mediaPlayer === newPlayer) {
                            releasePlayerLocked()
                            _state.value = VoicePlaybackState()
                        }
                    }
                    true
                }
                newPlayer.start()
                mediaPlayer = newPlayer
                _state.value = VoicePlaybackState(
                    activeMessageId = messageId,
                    isPlaying = true,
                    positionMs = 0L,
                    durationMs = actualDuration
                )
                startProgressTickerLocked(messageId)
            }
            if (result.isFailure) {
                Log.w(TAG, "Voice playback failed: ${result.exceptionOrNull()?.message}")
                runCatching { newPlayer.release() }
                mediaPlayer = null
                _state.value = VoicePlaybackState()
            }
        }
    }

    fun seekToFraction(messageId: String, fraction: Float) {
        synchronized(lock) {
            val current = _state.value
            if (current.activeMessageId != messageId) return
            val player = mediaPlayer ?: return
            val targetMs = (current.durationMs * fraction.coerceIn(0f, 1f)).toInt()
            runCatching { player.seekTo(targetMs) }
            _state.value = current.copy(positionMs = targetMs.toLong())
        }
    }

    fun stop() {
        synchronized(lock) {
            releasePlayerLocked()
            _state.value = VoicePlaybackState()
        }
    }

    private fun startProgressTickerLocked(messageId: String) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(100L)
                synchronized(lock) {
                    val player = mediaPlayer ?: return@launch
                    val cur = _state.value
                    if (cur.activeMessageId != messageId || !cur.isPlaying) return@launch
                    val pos = runCatching { player.currentPosition.toLong() }.getOrDefault(cur.positionMs)
                    _state.value = cur.copy(positionMs = pos.coerceIn(0L, cur.durationMs))
                }
            }
        }
    }

    private fun releasePlayerLocked() {
        progressJob?.cancel()
        progressJob = null
        val old = mediaPlayer
        mediaPlayer = null
        if (old != null) {
            runCatching { old.stop() }
            runCatching { old.release() }
        }
    }

    private class ByteArrayMediaDataSource(
        private val data: ByteArray
    ) : MediaDataSource() {
        override fun getSize(): Long = data.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L || position >= data.size) return -1
            val posInt = position.toInt()
            val available = data.size - posInt
            val toCopy = minOf(size, available)
            if (toCopy <= 0) return 0
            System.arraycopy(data, posInt, buffer, offset, toCopy)
            return toCopy
        }

        override fun close() = Unit
    }

    companion object {
        private const val TAG = "VoiceNotePlayer"
    }
}

