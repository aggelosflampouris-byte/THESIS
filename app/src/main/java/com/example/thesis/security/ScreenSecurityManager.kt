package com.example.thesis.security

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.function.Consumer

/**
 * Politica de seguridad de pantalla inmutable para impedir capturas y grabaciones de forma permanente.
 */
object ScreenSecurityPolicy {
    const val RECORDING_STATE_VISIBLE: Int = 1
    val DIALOG_SECURE_POLICY: SecureFlagPolicy = SecureFlagPolicy.SecureOn

    fun isRecordingVisible(recordingState: Int): Boolean =
        recordingState == RECORDING_STATE_VISIBLE

    fun shouldShowPrivacyShield(isScreenRecordingActive: Boolean): Boolean =
        isScreenRecordingActive
}

object ScreenSecurityManager {
    private const val LEGACY_PREF_NAME = "thesis_screen_security"

    // Eliminamos preferencias heredadas para que no persista ningun estado previo desactivado.
    fun purgeLegacyPreference(appContext: Context) {
        runCatching { appContext.deleteSharedPreferences(LEGACY_PREF_NAME) }
    }

    // Aplicamos FLAG_SECURE sin opcion de desactivacion para bloquear capturas de forma permanente.
    fun applyWindowProtection(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // En Android 13+ bloqueamos ademas la captura automatica de vista previa en Recientes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { activity.setRecentsScreenshotEnabled(false) }
        }
    }

    fun dialogProperties(
        dismissOnBackPress: Boolean = true,
        dismissOnClickOutside: Boolean = true
    ): DialogProperties {
        return DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            securePolicy = ScreenSecurityPolicy.DIALOG_SECURE_POLICY
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
