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
        private const val LEGACY_SERVICE_CHANNEL_ID = "xmtp_mls_connection_service"
        private const val LEGACY_SERVICE_NOTIFICATION_ID = 9001
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

        // Eliminamos el canal del antiguo servicio continuo para no mostrar una notificacion permanente tras actualizar.
        if (manager.getNotificationChannel(LEGACY_SERVICE_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_SERVICE_CHANNEL_ID)
        }
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(LEGACY_SERVICE_NOTIFICATION_ID)
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

    fun openNotificationSettings() {
        ensureChannelCreated()
        runCatching {
            val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
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
            .setName("Εσείς")
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
