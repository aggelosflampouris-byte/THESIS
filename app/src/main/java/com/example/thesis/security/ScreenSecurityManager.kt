package com.example.thesis.security

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.function.Consumer

interface ScreenSecurityStorage {
    val isScreenProtectionEnabled: StateFlow<Boolean>
    fun setScreenProtectionEnabled(enabled: Boolean)
    fun resetToDefault()
}

class InMemoryScreenSecurityStorage(
    initialEnabled: Boolean = ScreenSecurityPolicy.DEFAULT_SCREEN_PROTECTION_ENABLED
) : ScreenSecurityStorage {

    private val lock = Any()
    private val _isScreenProtectionEnabled = MutableStateFlow(initialEnabled)
    override val isScreenProtectionEnabled: StateFlow<Boolean> = _isScreenProtectionEnabled.asStateFlow()

    override fun setScreenProtectionEnabled(enabled: Boolean) {
        synchronized(lock) {
            _isScreenProtectionEnabled.value = enabled
        }
    }

    override fun resetToDefault() {
        synchronized(lock) {
            _isScreenProtectionEnabled.value = ScreenSecurityPolicy.DEFAULT_SCREEN_PROTECTION_ENABLED
        }
    }
}

internal class SharedPrefsScreenSecurityStore(context: Context) : ScreenSecurityStorage {

    private val appContext: Context = context.applicationContext
    private val lock = Any()

    companion object {
        private const val PREF_NAME = "thesis_screen_security"
        private const val KEY_PROTECTION_ENABLED = "screen_protection_enabled"
    }

    private fun prefs() = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _isScreenProtectionEnabled = MutableStateFlow(loadInitialSetting())
    override val isScreenProtectionEnabled: StateFlow<Boolean> = _isScreenProtectionEnabled.asStateFlow()

    private fun loadInitialSetting(): Boolean = synchronized(lock) {
        prefs().getBoolean(KEY_PROTECTION_ENABLED, ScreenSecurityPolicy.DEFAULT_SCREEN_PROTECTION_ENABLED)
    }

    override fun setScreenProtectionEnabled(enabled: Boolean) {
        synchronized(lock) {
            // Confirmamos de forma sincrona para mantener la politica de seguridad ante reinicios inmediatos.
            prefs().edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).commit()
            _isScreenProtectionEnabled.value = enabled
        }
    }

    override fun resetToDefault() {
        synchronized(lock) {
            prefs().edit().clear().commit()
            runCatching { appContext.deleteSharedPreferences(PREF_NAME) }
            _isScreenProtectionEnabled.value = ScreenSecurityPolicy.DEFAULT_SCREEN_PROTECTION_ENABLED
        }
    }
}

object ScreenSecurityPolicy {
    const val DEFAULT_SCREEN_PROTECTION_ENABLED: Boolean = true
    const val RECORDING_STATE_VISIBLE: Int = 1

    fun resolveDialogSecurePolicy(isProtectionEnabled: Boolean): SecureFlagPolicy {
        return if (isProtectionEnabled) SecureFlagPolicy.SecureOn else SecureFlagPolicy.SecureOff
    }

    fun isRecordingVisible(recordingState: Int): Boolean =
        recordingState == RECORDING_STATE_VISIBLE

    fun shouldShowPrivacyShield(
        isProtectionEnabled: Boolean,
        isScreenRecordingActive: Boolean
    ): Boolean = isProtectionEnabled && isScreenRecordingActive

    fun shouldShowRecordingWarningBanner(
        isProtectionEnabled: Boolean,
        isScreenRecordingActive: Boolean
    ): Boolean = !isProtectionEnabled && isScreenRecordingActive
}

object ScreenSecurityManager {

    fun applyWindowProtection(activity: Activity, enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // En Android 13+ bloqueamos ademas la captura automatica de vista previa en Recientes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { activity.setRecentsScreenshotEnabled(!enabled) }
        }
    }

    fun dialogProperties(
        enabled: Boolean,
        dismissOnBackPress: Boolean = true,
        dismissOnClickOutside: Boolean = true
    ): DialogProperties {
        return DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            securePolicy = ScreenSecurityPolicy.resolveDialogSecurePolicy(enabled)
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun registerScreenCaptureObserver(
        activity: Activity,
        onCaptureDetected: () -> Unit
    ): Any? {
        return runCatching {
            val callback = Activity.ScreenCaptureCallback {
                onCaptureDetected()
            }
            activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
            callback
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun unregisterScreenCaptureObserver(activity: Activity, handle: Any?) {
        val callback = handle as? Activity.ScreenCaptureCallback ?: return
        runCatching {
            activity.unregisterScreenCaptureCallback(callback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun registerScreenRecordingObserver(
        activity: Activity,
        onRecordingChanged: (Boolean) -> Unit
    ): Consumer<Int>? {
        return runCatching {
            val consumer = Consumer<Int> { state ->
                onRecordingChanged(ScreenSecurityPolicy.isRecordingVisible(state))
            }
            // Evaluamos de inmediato el estado inicial por si la grabacion ya habia comenzado antes de abrir la app.
            val initialState = activity.windowManager.addScreenRecordingCallback(
                activity.mainExecutor,
                consumer
            )
            onRecordingChanged(ScreenSecurityPolicy.isRecordingVisible(initialState))
            consumer
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun unregisterScreenRecordingObserver(activity: Activity, handle: Consumer<Int>?) {
        if (handle == null) return
        runCatching {
            activity.windowManager.removeScreenRecordingCallback(handle)
        }
    }
}
