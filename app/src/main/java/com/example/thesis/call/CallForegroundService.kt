package com.example.thesis.call

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.thesis.R
import com.example.thesis.ThesisApplication

// Servicio en primer plano tipo microphone iniciado solo tras concederse RECORD_AUDIO (requisito API 34+).
internal class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android exige startForeground() tras startForegroundService(); lo llamamos siempre primero
        // para evitar ForegroundServiceDidNotStartInTimeException si la llamada termina enseguida.
        val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) == true
        val notification = buildNotificationSafely(intent)
        val started = enterForeground(notification, isVideo)
        if (!started) {
            Log.e(TAG, "Failed to enter call foreground service")
            startPending = false
            stopSelf()
            return START_NOT_STICKY
        }
        isForegroundActive = true
        startPending = false

        if (intent?.action == ACTION_STOP || stopRequested) {
            stopRequested = false
            stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isForegroundActive = false
        startPending = false
        stopRequested = false
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification, isVideo: Boolean): Boolean {
        val baseMicType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasRecordAudioPermission(this)) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isVideo && hasCameraPermission(this)) {
            baseMicType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            baseMicType
        }
        val primary = runCatching {
            ServiceCompat.startForeground(this, CallNotificationManager.NOTIFICATION_ID_ONGOING, notification, fgsType)
        }
        if (primary.isSuccess) return true
        Log.w(TAG, "startForeground with call notification failed: ${primary.exceptionOrNull()}")
        // Si Android rechaza el tipo camera o CallStyle, reintentamos con solo microfono y notificacion basica.
        if (fgsType != baseMicType) {
            val micOnly = runCatching {
                ServiceCompat.startForeground(this, CallNotificationManager.NOTIFICATION_ID_ONGOING, notification, baseMicType)
            }
            if (micOnly.isSuccess) return true
        }
        return runCatching {
            ServiceCompat.startForeground(
                this,
                CallNotificationManager.NOTIFICATION_ID_ONGOING,
                buildFallbackNotification(),
                baseMicType
            )
        }.isSuccess
    }

    private fun buildNotificationSafely(intent: Intent?): Notification {
        val container = (applicationContext as? ThesisApplication)?.sessionContainer
        val callId = intent?.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME).orEmpty().ifBlank { "Επαφή XMTP" }
        val statusLabel = intent?.getStringExtra(EXTRA_STATUS_LABEL).orEmpty().ifBlank { "Κλήση φωνής σε εξέλιξη" }
        return runCatching {
            requireNotNull(container).callNotificationManager.buildOngoingCallNotification(
                callId = callId,
                conversationId = conversationId,
                peerName = peerName,
                statusLabel = statusLabel
            )
        }.getOrElse { buildFallbackNotification() }
    }

    private fun buildFallbackNotification(): Notification {
        val container = (applicationContext as? ThesisApplication)?.sessionContainer
        runCatching { container?.callNotificationManager?.ensureChannelCreated() }
        val spec = CallNotificationPolicy.specFor(CallNotificationKind.ONGOING)
        return NotificationCompat.Builder(this, CallNotificationManager.ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_phone)
            .setContentTitle("Κλήση φωνής")
            .setContentText("Κλήση φωνής σε εξέλιξη")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(spec.notificationPriority)
            .setSilent(spec.silent)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .build()
    }

    private fun stopForegroundAndSelf() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        isForegroundActive = false
        stopSelf()
    }

    companion object {
        private const val TAG = "CallForegroundService"
        private const val ACTION_START = "com.example.thesis.call.ACTION_START_FGS"
        private const val ACTION_STOP = "com.example.thesis.call.ACTION_STOP_FGS"
        private const val EXTRA_CALL_ID = "extra_call_id"
        private const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        private const val EXTRA_PEER_NAME = "extra_peer_name"
        private const val EXTRA_STATUS_LABEL = "extra_status_label"
        private const val EXTRA_IS_VIDEO = "extra_is_video"

        @Volatile
        private var startPending: Boolean = false

        @Volatile
        private var isForegroundActive: Boolean = false

        @Volatile
        private var stopRequested: Boolean = false

        fun hasRecordAudioPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasCameraPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun start(
            context: Context,
            callId: String,
            conversationId: String,
            peerName: String,
            statusLabel: String,
            isVideo: Boolean = false
        ): Boolean {
            if (!hasRecordAudioPermission(context)) return false
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, CallForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_STATUS_LABEL, statusLabel)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            stopRequested = false
            startPending = true
            val launched = runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }.isSuccess
            if (!launched) startPending = false
            return launched
        }

        fun stop(context: Context) {
            if (startPending && !isForegroundActive) {
                // Detener antes de startForeground() provoca un cierre forzado; diferimos la parada al servicio.
                stopRequested = true
                return
            }
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, CallForegroundService::class.java)
            runCatching { appCtx.stopService(intent) }
        }
    }
}
