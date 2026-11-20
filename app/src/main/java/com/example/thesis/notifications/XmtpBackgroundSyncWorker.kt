package com.example.thesis.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.thesis.ThesisApplication
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.CancellationException

internal class XmtpBackgroundSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "XmtpBgSyncWorker"
    }

    // No llamamos a setForeground para evitar notificaciones fijas mientras sincronizamos en segundo plano.
    override suspend fun doWork(): Result {
        val container = (applicationContext as? ThesisApplication)?.sessionContainer
            ?: return Result.success()

        if (!container.keystoreManager.hasPersistedSession()) {
            BackgroundSyncScheduler.cancelAll(applicationContext)
            return Result.success()
        }

        if (XmtpMessagingService.isRunning) {
            return Result.success()
        }

        if (container.isInForeground() && container.engine.state.value is XmtpEngineState.Ready) {
            return Result.success()
        }

        try {
            container.syncMessagesInBackground()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Background sync worker failed: ${e::class.java.simpleName}")
        }

        if (container.keystoreManager.hasPersistedSession() && !container.isInForeground()) {
            BackgroundSyncScheduler.scheduleNextAlarm(applicationContext)
        }
        return Result.success()
    }
}
