package com.example.thesis.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.thesis.MainActivity
import com.example.thesis.R

internal class MessageNotificationManager(context: Context) {

    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "MessageNotifManager"
        const val CHANNEL_ID = "xmtp_mls_priority_messages"
        const val EXTRA_CONVERSATION_ID = "com.example.thesis.extra.CONVERSATION_ID"
        private val VIBRATION_PATTERN = longArrayOf(0L, 180L, 100L, 180L)
    }

    fun ensureChannelCreated() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Μηνύματα XMTP MLS (Προτεραιότητα)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ειδοποιήσεις υψηλής προτεραιότητας για νέα κρυπτογραφημένα μηνύματα MLS"
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
        }
        manager.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun openNotificationSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }
    }

    @SuppressLint("MissingPermission")
    fun showIncomingMessageNotification(
        conversationId: String,
        title: String,
        bodyPreview: String,
        timestampMs: Long
    ): Boolean {
        if (!hasNotificationPermission()) {
            return false
        }
        ensureChannelCreated()

        val notificationId = MessageNotificationPolicy.notificationIdFor(conversationId)
        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Ocultamos el contenido en la pantalla de bloqueo para proteger la confidencialidad del mensaje E2EE.
        val publicVersion = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle("XMTP v4 (MLS)")
            .setContentText("Νέο κρυπτογραφημένο μήνυμα MLS")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(title)
            .setContentText(bodyPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyPreview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setWhen(if (timestampMs > 0L) timestampMs else System.currentTimeMillis())
            .setContentIntent(contentIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
            true
        }.onFailure { err ->
            Log.w(TAG, "Failed to post priority notification: ${err::class.java.simpleName}")
        }.getOrDefault(false)
    }

    fun cancelConversationNotification(conversationId: String) {
        if (conversationId.isBlank()) return
        runCatching {
            NotificationManagerCompat.from(appContext)
                .cancel(MessageNotificationPolicy.notificationIdFor(conversationId))
        }
    }

    fun cancelAllNotifications() {
        runCatching {
            NotificationManagerCompat.from(appContext).cancelAll()
        }
    }
}
