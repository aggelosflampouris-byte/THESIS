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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.thesis.MainActivity
import com.example.thesis.R

internal class MessageNotificationManager(context: Context) {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var cachedBadgeBitmap: Bitmap? = null

    companion object {
        private const val TAG = "MessageNotifManager"
        private const val LEGACY_CHANNEL_ID = "xmtp_mls_priority_messages"
        const val CHANNEL_ID = "xmtp_mls_priority_messages_v2"
        const val SERVICE_CHANNEL_ID = "xmtp_mls_connection_service"
        const val SERVICE_NOTIFICATION_ID = 9001
        const val ACTION_RESTORE_SERVICE_NOTIFICATION = "com.example.thesis.action.RESTORE_SERVICE_NOTIFICATION"
        private const val SERVICE_HIDE_ACTION_REQUEST_CODE = 9003
        private const val SERVICE_RESTORE_REQUEST_CODE = 9004
        const val EXTRA_CONVERSATION_ID = "com.example.thesis.extra.CONVERSATION_ID"
        private const val ACCENT_COLOR = 0xFF315C54.toInt()
        private val VIBRATION_PATTERN = longArrayOf(0L, 250L, 120L, 250L)
    }

    fun ensureChannelCreated() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return

        // Limpiamos el canal anterior para que Android aplique importancia alta y sonido en actualizaciones.
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val messageChannel = NotificationChannel(
                CHANNEL_ID,
                "Μηνύματα XMTP MLS (Υψηλή Προτεραιότητα)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ειδοποιήσεις υψηλής προτεραιότητας για νέα κρυπτογραφημένα μηνύματα MLS"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                enableLights(true)
                lightColor = ACCENT_COLOR
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
            }
            manager.createNotificationChannel(messageChannel)
        }

        // Canal aislado de importancia minima para poder ocultarlo sin silenciar las alertas de mensajes.
        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Σύνδεση Παρασκηνίου XMTP MLS",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Σιωπηλή σύνδεση για άμεση λήψη μηνυμάτων εκτός εφαρμογής (μπορεί να απενεργοποιηθεί χωρίς να σταματήσουν οι ειδοποιήσεις μηνυμάτων)"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(serviceChannel)
        }
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

    fun isBackgroundServiceChannelEnabled(): Boolean {
        ensureChannelCreated()
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return false
        }
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(SERVICE_CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun openNotificationSettings() = openChannelSettings(CHANNEL_ID)

    fun openBackgroundServiceChannelSettings() = openChannelSettings(SERVICE_CHANNEL_ID)

    private fun openChannelSettings(channelId: String) {
        ensureChannelCreated()
        runCatching {
            val channelIntent = createChannelSettingsIntent(channelId).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(channelIntent)
        }.onFailure {
            runCatching {
                val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(appIntent)
            }
        }
    }

    private fun createChannelSettingsIntent(channelId: String): Intent {
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        }
    }

    fun buildForegroundServiceNotification(): Notification {
        ensureChannelCreated()
        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            SERVICE_NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hideChannelIntent = createChannelSettingsIntent(SERVICE_CHANNEL_ID).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val hideChannelPendingIntent = PendingIntent.getActivity(
            appContext,
            SERVICE_HIDE_ACTION_REQUEST_CODE,
            hideChannelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(appContext, XmtpNotificationReceiver::class.java).apply {
            action = ACTION_RESTORE_SERVICE_NOTIFICATION
        }
        val restorePendingIntent = PendingIntent.getBroadcast(
            appContext,
            SERVICE_RESTORE_REQUEST_CODE,
            restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Bloqueamos Borrar todo y restauramos al instante si Android 14+ permite deslizarla, hasta pulsar «Απόκρυψη».
        return NotificationCompat.Builder(appContext, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setColor(ACCENT_COLOR)
            .setContentTitle("XMTP MLS Ενεργή Λήψη")
            .setContentText("Πατήστε «Απόκρυψη» για να κρύψετε αυτό το εικονίδιο")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setDeleteIntent(restorePendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, "Απόκρυψη", hideChannelPendingIntent)
            .build()
            .apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    @SuppressLint("MissingPermission")
    fun repostForegroundServiceNotification(): Boolean {
        if (!hasNotificationPermission() || !isBackgroundServiceChannelEnabled()) {
            return false
        }
        return runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(SERVICE_NOTIFICATION_ID, buildForegroundServiceNotification())
            true
        }.onFailure { err ->
            Log.w(TAG, "Failed to restore foreground service notification: ${err::class.java.simpleName}")
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun showIncomingMessageNotification(
        conversationId: String,
        title: String,
        bodyPreview: String,
        timestampMs: Long,
        localUserName: String = "Εσείς"
    ): Boolean {
        if (!hasNotificationPermission()) {
            return false
        }
        ensureChannelCreated()

        val resolvedTimestampMs = if (timestampMs > 0L) timestampMs else System.currentTimeMillis()
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

        val largeBadge = getOrCreateBadgeBitmap()

        // Ocultamos el contenido en la pantalla de bloqueo para proteger la confidencialidad del mensaje E2EE.
        val publicVersion = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setColor(ACCENT_COLOR)
            .setLargeIcon(largeBadge)
            .setContentTitle("XMTP v4 (MLS)")
            .setContentText("Νέο κρυπτογραφημένο μήνυμα MLS")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val localUser = Person.Builder()
            .setName(localUserName.ifBlank { "Εσείς" })
            .build()
        val sender = Person.Builder()
            .setName(title)
            .setKey(conversationId)
            .build()
        val messagingStyle = NotificationCompat.MessagingStyle(localUser)
            .setConversationTitle(title)
            .addMessage(bodyPreview, resolvedTimestampMs, sender)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setColor(ACCENT_COLOR)
            .setLargeIcon(largeBadge)
            .setContentTitle(title)
            .setContentText(bodyPreview)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(VIBRATION_PATTERN)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setWhen(resolvedTimestampMs)
            .setContentIntent(contentIntent)

        return runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
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

    private fun getOrCreateBadgeBitmap(): Bitmap? {
        cachedBadgeBitmap?.let { return it }
        return runCatching {
            val drawable = ContextCompat.getDrawable(appContext, R.drawable.ic_notification_message_badge)
                ?: return null
            val sizePx = (48 * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(96)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            cachedBadgeBitmap = bitmap
            bitmap
        }.getOrNull()
    }
}
