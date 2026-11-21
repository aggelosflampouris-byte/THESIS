package com.example.thesis.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.thesis.call.CallNotificationManager
import com.example.thesis.ThesisApplication

internal class XmtpNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val container = (context.applicationContext as? ThesisApplication)?.sessionContainer ?: return

        when (action) {
            MessageNotificationManager.ACTION_RESTORE_SERVICE_NOTIFICATION -> {
                // Reponemos la notificacion fija si el usuario la deslizo sin haber desactivado su canal con «Απόκρυψη».
                if (MessageNotificationPolicy.shouldRestoreServiceNotification(
                        isServiceRunning = XmtpMessagingService.isRunning,
                        hasPersistedSession = container.keystoreManager.hasPersistedSession(),
                        isServiceChannelEnabled = container.notificationManager.isBackgroundServiceChannelEnabled()
                    )
                ) {
                    container.notificationManager.repostForegroundServiceNotification()
                }
            }
            BackgroundSyncScheduler.ACTION_BACKGROUND_POLL -> {
                if (!container.keystoreManager.hasPersistedSession()) {
                    BackgroundSyncScheduler.cancelAll(context)
                    return
                }
                // Reprogramamos la siguiente alarma de inmediato para mantener el sondeo periodico en espera.
                BackgroundSyncScheduler.scheduleNextAlarm(context)
                // Actuamos solo como respaldo cuando el servicio activo no esta en ejecucion para evitar ciclos duplicados.
                if (!container.isInForeground() && !XmtpMessagingService.isRunning) {
                    BackgroundSyncScheduler.enqueueImmediateSync(context)
                }
            }
            CallNotificationManager.ACTION_DECLINE_CALL -> {
                val callId = intent.getStringExtra(CallNotificationManager.EXTRA_CALL_ID)
                container.callSessionManager.declineIncomingCall(callId)
            }
            CallNotificationManager.ACTION_HANGUP_CALL -> {
                val callId = intent.getStringExtra(CallNotificationManager.EXTRA_CALL_ID)
                container.callSessionManager.hangup(callId)
            }
        }
    }
}
