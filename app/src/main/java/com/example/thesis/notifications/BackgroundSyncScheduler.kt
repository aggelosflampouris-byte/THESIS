package com.example.thesis.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object BackgroundSyncScheduler {

    const val PERIODIC_WORK_NAME = "xmtp_mls_periodic_sync"
    const val IMMEDIATE_WORK_NAME = "xmtp_mls_immediate_sync"
    const val ACTION_BACKGROUND_POLL = "com.example.thesis.action.BACKGROUND_POLL"
    const val ALARM_REQUEST_CODE = 9002
    const val ALARM_INTERVAL_MS = 60_000L
    const val PERIODIC_INTERVAL_MINUTES = 15L

    fun scheduleAll(context: Context) {
        val appContext = context.applicationContext
        schedulePeriodicWork(appContext)
        scheduleNextAlarm(appContext)
    }

    fun schedulePeriodicWork(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<XmtpBackgroundSyncWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    fun scheduleNextAlarm(context: Context, delayMs: Long = ALARM_INTERVAL_MS) {
        val appContext = context.applicationContext
        runCatching {
            val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return@runCatching
            // Usamos setAndAllowWhileIdle para operar en segundo plano sin requerir permisos especiales de alarma exacta.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                buildAlarmPendingIntent(appContext)
            )
        }
    }

    fun enqueueImmediateSync(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<XmtpBackgroundSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val pendingIntent = buildAlarmPendingIntent(appContext)
            appContext.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        runCatching {
            val workManager = WorkManager.getInstance(appContext)
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }

    private fun buildAlarmPendingIntent(appContext: Context): PendingIntent {
        val intent = Intent(appContext, XmtpNotificationReceiver::class.java).apply {
            action = ACTION_BACKGROUND_POLL
        }
        return PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
