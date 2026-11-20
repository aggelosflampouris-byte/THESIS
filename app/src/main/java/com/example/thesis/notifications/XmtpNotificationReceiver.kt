package com.example.thesis.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.thesis.ThesisApplication

internal class XmtpNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BackgroundSyncScheduler.ACTION_BACKGROUND_POLL) return

        val container = (context.applicationContext as? ThesisApplication)?.sessionContainer ?: return
        if (!container.keystoreManager.hasPersistedSession()) {
            BackgroundSyncScheduler.cancelAll(context)
            return
        }

        // Reprogramamos la siguiente alarma de inmediato para mantener el sondeo periodico en espera.
        BackgroundSyncScheduler.scheduleNextAlarm(context)
        if (!container.isInForeground()) {
            BackgroundSyncScheduler.enqueueImmediateSync(context)
        }
    }
}
