package com.example.thesis.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.thesis.MainActivity
import com.example.thesis.R
import com.example.thesis.notifications.XmtpNotificationReceiver

// Notificaciones nativas CallStyle separadas del canal de chat para timbre entrante y llamada en curso.
internal class CallNotificationManager(private val appContext: Context) {
    private val notificationManager = NotificationManagerCompat.from(appContext)

    fun ensureChannelCreated() {
        val incomingSpec = CallNotificationPolicy.specFor(CallNotificationKind.INCOMING)
        val ongoingSpec = CallNotificationPolicy.specFor(CallNotificationKind.ONGOING)

        val incomingChannel = NotificationChannel(
            incomingSpec.channelId,
            "Εισερχόμενες κλήσεις (MLS + WebRTC)",
            incomingSpec.channelImportance
        ).apply {
            description = "Ειδοποιήσεις υψηλής προτεραιότητας για εισερχόμενες κλήσεις φωνής και βίντεο."
            // El audio de llamada lo controla CallAudioController para evitar doble timbre simultaneo.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // Canal separado de baja importancia para que llamar o mantener una llamada no dispare banners intrusivos.
        val ongoingChannel = NotificationChannel(
            ongoingSpec.channelId,
            "Ενεργές & εξερχόμενες κλήσεις",
            ongoingSpec.channelImportance
        ).apply {
            description = "Σιωπηλή ειδοποίηση χαμηλής προτεραιότητας για εξερχόμενες και ενεργές κλήσεις."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        notificationManager.createNotificationChannel(incomingChannel)
        notificationManager.createNotificationChannel(ongoingChannel)
    }

    fun showIncomingCallNotification(
        callId: String,
        conversationId: String,
        callerName: String,
        isVideo: Boolean = false
    ) {
        if (!hasPostNotificationsPermission()) return
        ensureChannelCreated()
        val notification = buildIncomingCallNotification(callId, conversationId, callerName, isVideo)
        postNotificationSafely(NOTIFICATION_ID_INCOMING, notification)
    }

    fun updateOngoingCallNotification(
        callId: String,
        conversationId: String,
        peerName: String,
        statusLabel: String
    ) {
        if (!hasPostNotificationsPermission()) return
        ensureChannelCreated()
        val notification = buildOngoingCallNotification(callId, conversationId, peerName, statusLabel)
        postNotificationSafely(NOTIFICATION_ID_ONGOING, notification)
    }

    fun buildIncomingCallNotification(
        callId: String,
        conversationId: String,
        callerName: String,
        isVideo: Boolean = false
    ): Notification {
        ensureChannelCreated()
        val safeName = callerName.ifBlank { "Επαφή XMTP" }
        val person = Person.Builder()
            .setName(safeName)
            .setImportant(true)
            .build()

        val contentPi = buildActivityCallIntent(
            requestCode = 6101,
            callId = callId,
            conversationId = conversationId,
            callAction = ACTION_OPEN_CALL_UI
        )
        val acceptPi = buildActivityCallIntent(
            requestCode = 6102,
            callId = callId,
            conversationId = conversationId,
            callAction = ACTION_ACCEPT_CALL
        )
        val declinePi = buildReceiverCallIntent(
            requestCode = 6103,
            action = ACTION_DECLINE_CALL,
            callId = callId,
            conversationId = conversationId
        )

        val incomingText = if (isVideo) {
            "Εισερχόμενη κρυπτογραφημένη βιντεοκλήση"
        } else {
            "Εισερχόμενη κρυπτογραφημένη κλήση φωνής"
        }

        val spec = CallNotificationPolicy.specFor(CallNotificationKind.INCOMING)

        return NotificationCompat.Builder(appContext, spec.channelId)
            .setSmallIcon(if (isVideo) R.drawable.ic_videocam else R.drawable.ic_call_phone)
            .setContentTitle(safeName)
            .setContentText(incomingText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(spec.notificationPriority)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPi)
            .apply {
                if (spec.useFullScreenIntent) {
                    setFullScreenIntent(contentPi, true)
                }
            }
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, declinePi, acceptPi))
            .build()
    }

    fun buildOngoingCallNotification(
        callId: String,
        conversationId: String,
        peerName: String,
        statusLabel: String
    ): Notification {
        ensureChannelCreated()
        val spec = CallNotificationPolicy.specFor(CallNotificationKind.ONGOING)
        val safeName = peerName.ifBlank { "Επαφή XMTP" }
        val person = Person.Builder()
            .setName(safeName)
            .build()

        val contentPi = buildActivityCallIntent(
            requestCode = 6104,
            callId = callId,
            conversationId = conversationId,
            callAction = ACTION_OPEN_CALL_UI
        )
        val hangupPi = buildReceiverCallIntent(
            requestCode = 6105,
            action = ACTION_HANGUP_CALL,
            callId = callId,
            conversationId = conversationId
        )

        return NotificationCompat.Builder(appContext, spec.channelId)
            .setSmallIcon(R.drawable.ic_call_phone)
            .setContentTitle(safeName)
            .setContentText(statusLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(spec.notificationPriority)
            .setSilent(spec.silent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangupPi))
            .build()
    }

    fun cancelIncomingCallNotification() {
        notificationManager.cancel(NOTIFICATION_ID_INCOMING)
    }

    fun cancelOngoingCallNotification() {
        notificationManager.cancel(NOTIFICATION_ID_ONGOING)
    }

    fun cancelAllCallNotifications() {
        notificationManager.cancel(NOTIFICATION_ID_INCOMING)
        notificationManager.cancel(NOTIFICATION_ID_ONGOING)
    }

    private fun buildActivityCallIntent(
        requestCode: Int,
        callId: String,
        conversationId: String,
        callAction: String
    ): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_ACTION, callAction)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildReceiverCallIntent(
        requestCode: Int,
        action: String,
        callId: String,
        conversationId: String
    ): PendingIntent {
        val intent = Intent(appContext, XmtpNotificationReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun postNotificationSafely(notificationId: Int, notification: Notification) {
        // Verificamos POST_NOTIFICATIONS antes de llegar aqui para evitar SecurityException en API 33+.
        runCatching { notificationManager.notify(notificationId, notification) }
    }

    companion object {
        const val CHANNEL_ID = CallNotificationPolicy.INCOMING_CHANNEL_ID
        const val ONGOING_CHANNEL_ID = CallNotificationPolicy.ONGOING_CHANNEL_ID
        const val NOTIFICATION_ID_INCOMING = 4201
        const val NOTIFICATION_ID_ONGOING = 4202

        const val EXTRA_CALL_ID = "com.example.thesis.call.EXTRA_CALL_ID"
        const val EXTRA_CONVERSATION_ID = "com.example.thesis.call.EXTRA_CONVERSATION_ID"
        const val EXTRA_CALL_ACTION = "com.example.thesis.call.EXTRA_CALL_ACTION"

        const val ACTION_ACCEPT_CALL = "com.example.thesis.call.ACTION_ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "com.example.thesis.call.ACTION_DECLINE_CALL"
        const val ACTION_HANGUP_CALL = "com.example.thesis.call.ACTION_HANGUP_CALL"
        const val ACTION_OPEN_CALL_UI = "com.example.thesis.call.ACTION_OPEN_CALL_UI"
    }
}
