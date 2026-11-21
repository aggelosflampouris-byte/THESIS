package com.example.thesis.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.thesis.ThesisApplication

// Servicio en primer plano tipo microphone iniciado solo tras concederse RECORD_AUDIO (requisito API 34+).
internal class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        if (!hasRecordAudioPermission(this)) {
            Log.w(TAG, "Missing RECORD_AUDIO permission; stopping CallForegroundService")
            stopSelf()
            return START_NOT_STICKY
        }

        val container = (applicationContext as? ThesisApplication)?.sessionContainer
        if (container == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val callId = intent?.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME).orEmpty().ifBlank { "Επαφή XMTP" }
        val statusLabel = intent?.getStringExtra(EXTRA_STATUS_LABEL).orEmpty().ifBlank { "Κλήση φωνής σε εξέλιξη" }

        val notification = container.callNotificationManager.buildOngoingCallNotification(
            callId = callId,
            conversationId = conversationId,
            peerName = peerName,
            statusLabel = statusLabel
        )
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                CallNotificationManager.NOTIFICATION_ID_ONGOING,
                notification,
                fgsType
            )
        }.isSuccess

        if (!started) {
            Log.e(TAG, "Failed to enter microphone foreground service")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
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

        fun hasRecordAudioPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun start(
            context: Context,
            callId: String,
            conversationId: String,
            peerName: String,
            statusLabel: String
        ): Boolean {
            if (!hasRecordAudioPermission(context)) return false
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, CallForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_STATUS_LABEL, statusLabel)
            }
            return runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }.isSuccess
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, CallForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { appCtx.stopService(intent) }
        }
    }
}
