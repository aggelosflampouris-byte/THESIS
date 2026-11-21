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

// Controlador de foco de audio VoIP, altavoz/Bluetooth y tono de llamada compatible con API 26 a API 37.
internal class CallAudioController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private var activeFocusRequest: AudioFocusRequest? = null
    private var activeRingtone: Ringtone? = null
    private var speakerEnabled: Boolean = false
    private var sessionActive: Boolean = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio focus changed: $focusChange")
    }

    fun startIncomingRinging() {
        stopRinging()
        val mgr = audioManager ?: return
        if (mgr.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        if (mgr.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val ringtone = RingtoneManager.getRingtone(appContext, uri)
                if (ringtone != null) {
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.isLooping = true
                    }
                    ringtone.play()
                    activeRingtone = ringtone
                }
            }
        }

        runCatching {
            val vibrator = resolveVibrator() ?: return@runCatching
            if (vibrator.hasVibrator()) {
                val pattern = longArrayOf(0L, 450L, 900L)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 1))
            }
        }
    }

    fun stopRinging() {
        runCatching { activeRingtone?.stop() }
        activeRingtone = null
        runCatching { resolveVibrator()?.cancel() }
    }

    fun startInCallAudio(speakerOn: Boolean = false) {
        stopRinging()
        speakerEnabled = speakerOn
        val mgr = audioManager ?: return
        if (!sessionActive) {
            requestVoiceAudioFocus(mgr)
            runCatching { mgr.mode = AudioManager.MODE_IN_COMMUNICATION }
            sessionActive = true
        }
        applyAudioRoute(speakerOn)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        speakerEnabled = enabled
        if (sessionActive) {
            applyAudioRoute(enabled)
        }
    }

    fun release() {
        stopRinging()
        speakerEnabled = false
        val mgr = audioManager ?: return
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

    private fun requestVoiceAudioFocus(mgr: AudioManager) {
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

    private fun applyAudioRoute(speakerOn: Boolean) {
        val mgr = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = mgr.availableCommunicationDevices
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

    companion object {
        private const val TAG = "CallAudioController"
    }
}
