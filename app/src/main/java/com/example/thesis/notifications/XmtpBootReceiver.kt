package com.example.thesis.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.thesis.ThesisApplication

internal class XmtpBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val container = (context.applicationContext as? ThesisApplication)?.sessionContainer ?: return
        if (container.keystoreManager.hasPersistedSession()) {
            BackgroundSyncScheduler.scheduleAll(context)
            BackgroundSyncScheduler.enqueueImmediateSync(context)
        }
    }
}
