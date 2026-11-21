package com.example.thesis.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Controlador de audio VoIP aislado en su propio hilo para que IPC de AudioManager/Ringtone nunca bloquee la UI ni las corrutinas de llamada.
internal class CallAudioController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val ringingDesired = AtomicBoolean(false)

    private val audioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ThesisCallAudioThread").apply { isDaemon = true }
    }

    private var activeFocusRequest: AudioFocusRequest? = null
    private var activeRingtone: Ringtone? = null
    private var speakerEnabled: Boolean = false
    private var sessionActive: Boolean = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio focus changed: $focusChange")
    }

    fun startIncomingRinging() {
        ringingDesired.set(true)
        runOnAudioThread {
            stopRingingOnExecutor()
            if (!ringingDesired.get()) return@runOnAudioThread
            val mgr = audioManager ?: return@runOnAudioThread
            if (mgr.ringerMode == AudioManager.RINGER_MODE_SILENT) return@runOnAudioThread

            if (mgr.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                runCatching {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val ringtone = RingtoneManager.getRingtone(appContext, uri)
                    if (ringtone != null && ringingDesired.get()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ringtone.isLooping = true
                        }
                        ringtone.play()
                        if (ringingDesired.get()) {
                            activeRingtone = ringtone
                        } else {
                            runCatching { ringtone.stop() }
                        }
                    }
                }
            }

            if (ringingDesired.get()) {
                runCatching {
                    val vibrator = resolveVibrator() ?: return@runCatching
                    if (vibrator.hasVibrator()) {
                        val pattern = longArrayOf(0L, 450L, 900L)
                        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 1))
                    }
                }
            }
        }
    }

    fun stopRinging() {
        ringingDesired.set(false)
        // Cancelamos la vibracion de inmediato y detenemos el Ringtone en el hilo dedicado de audio.
        runCatching { resolveVibrator()?.cancel() }
        runOnAudioThread {
            stopRingingOnExecutor()
        }
    }

    fun startInCallAudio(speakerOn: Boolean = false) {
        ringingDesired.set(false)
        runCatching { resolveVibrator()?.cancel() }
        runOnAudioThread {
            stopRingingOnExecutor()
            speakerEnabled = speakerOn
            val mgr = audioManager ?: return@runOnAudioThread
            if (!sessionActive) {
                requestVoiceAudioFocusOnExecutor(mgr)
                runCatching { mgr.mode = AudioManager.MODE_IN_COMMUNICATION }
                sessionActive = true
            }
            applyAudioRouteOnExecutor(speakerOn)
        }
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        runOnAudioThread {
            speakerEnabled = enabled
            if (sessionActive) {
                applyAudioRouteOnExecutor(enabled)
            }
        }
    }

    fun release() {
        ringingDesired.set(false)
        runCatching { resolveVibrator()?.cancel() }
        runOnAudioThread {
            stopRingingOnExecutor()
            speakerEnabled = false
            val mgr = audioManager ?: return@runOnAudioThread
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { mgr.clearCommunicationDevice() }
            } else {
                setLegacySpeakerphone(mgr, false)
            }
            runCatching {
                activeFocusRequest?.let { req ->
                    mgr.abandonAudioFocusRequest(req)
                }
            }
            activeFocusRequest = null
            if (sessionActive) {
                runCatching { mgr.mode = AudioManager.MODE_NORMAL }
                sessionActive = false
            }
        }
    }

    private fun stopRingingOnExecutor() {
        runCatching { activeRingtone?.stop() }
        activeRingtone = null
        runCatching { resolveVibrator()?.cancel() }
    }

    private fun requestVoiceAudioFocusOnExecutor(mgr: AudioManager) {
        if (activeFocusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        activeFocusRequest = req
        runCatching { mgr.requestAudioFocus(req) }
    }

    private fun applyAudioRouteOnExecutor(speakerOn: Boolean) {
        val mgr = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { mgr.availableCommunicationDevices }.getOrDefault(emptyList())
            val targetDevice = when {
                speakerOn -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else -> {
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
            }
            if (targetDevice != null) {
                runCatching { mgr.setCommunicationDevice(targetDevice) }
            } else {
                runCatching { mgr.clearCommunicationDevice() }
            }
        } else {
            setLegacySpeakerphone(mgr, speakerOn)
        }
    }

    @Suppress("DEPRECATION")
    private fun setLegacySpeakerphone(mgr: AudioManager, enabled: Boolean) {
        // Necesario unicamente en Android 8-10; API 31+ usa setCommunicationDevice().
        runCatching { mgr.isSpeakerphoneOn = enabled }
    }

    @Suppress("DEPRECATION")
    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            // getSystemService(Vibrator) solo se usa en Android < 12 antes de VibratorManager.
            appContext.getSystemService(Vibrator::class.java)
        }
    }

    private fun runOnAudioThread(block: () -> Unit) {
        runCatching {
            audioExecutor.execute {
                runCatching { block() }.onFailure { err ->
                    Log.w(TAG, "Audio task failed: ${err::class.java.simpleName}: ${err.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "CallAudioController"
    }
}
