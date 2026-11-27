package com.example.thesis.call

import android.app.NotificationManager
import androidx.core.app.NotificationCompat

// Clasificacion de las notificaciones de llamada para aislar alertas entrantes de llamadas salientes/activas.
internal enum class CallNotificationKind {
    INCOMING,
    ONGOING
}

// Configuracion de canal y prioridad aplicada a cada tipo de notificacion de llamada.
internal data class CallNotificationSpec(
    val channelId: String,
    val channelImportance: Int,
    val notificationPriority: Int,
    val useFullScreenIntent: Boolean,
    val silent: Boolean
)

// Politica centralizada que reserva la prioridad maxima exclusivamente para llamadas entrantes.
internal object CallNotificationPolicy {
    const val INCOMING_CHANNEL_ID = "xmtp_mls_voice_calls_v1"
    const val ONGOING_CHANNEL_ID = "xmtp_mls_ongoing_calls_v1"

    fun specFor(kind: CallNotificationKind): CallNotificationSpec {
        return when (kind) {
            CallNotificationKind.INCOMING -> CallNotificationSpec(
                channelId = INCOMING_CHANNEL_ID,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                notificationPriority = NotificationCompat.PRIORITY_MAX,
                useFullScreenIntent = true,
                silent = false
            )

            CallNotificationKind.ONGOING -> CallNotificationSpec(
                channelId = ONGOING_CHANNEL_ID,
                channelImportance = NotificationManager.IMPORTANCE_LOW,
                notificationPriority = NotificationCompat.PRIORITY_LOW,
                useFullScreenIntent = false,
                silent = true
            )
        }
    }
}

