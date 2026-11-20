package com.example.thesis.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.thesis.ThesisApplication
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class XmtpMessagingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    companion object {
        private const val TAG = "XmtpMessagingService"
        private const val BACKGROUND_POLL_INTERVAL_MS = 8_000L
        private const val FOREGROUND_POLL_INTERVAL_MS = 15_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context.applicationContext, XmtpMessagingService::class.java)
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { err ->
                Log.w(TAG, "Unable to start messaging service: ${err::class.java.simpleName}")
            }
        }

        fun stop(context: Context) {
            isRunning = false
            runCatching {
                val intent = Intent(context.applicationContext, XmtpMessagingService::class.java)
                context.applicationContext.stopService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as ThesisApplication).sessionContainer
        val fgNotification = container.notificationManager.buildForegroundServiceNotification()
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }

        // Promovemos de inmediato a primer plano porque Android 12+ exige respuesta instantanea al arranque.
        ServiceCompat.startForeground(
            this,
            MessageNotificationManager.SERVICE_NOTIFICATION_ID,
            fgNotification,
            fgType
        )

        if (!container.keystoreManager.hasPersistedSession() &&
            container.engine.state.value !is XmtpEngineState.Ready
        ) {
            isRunning = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        startMonitorLoop(container)
        return START_STICKY
    }

    private fun startMonitorLoop(container: ThesisSessionContainer) {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    container.syncMessagesInBackground()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Background sync tick failed: ${e::class.java.simpleName}")
                }
                val interval = if (container.isInForeground()) {
                    FOREGROUND_POLL_INTERVAL_MS
                } else {
                    BACKGROUND_POLL_INTERVAL_MS
                }
                delay(interval)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
