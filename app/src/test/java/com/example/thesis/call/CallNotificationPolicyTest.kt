package com.example.thesis.call

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNotificationPolicyTest {

    @Test
    fun test_incomingCallSpec_usesHighImportanceAndFullScreenIntent() {
        val spec = CallNotificationPolicy.specFor(CallNotificationKind.INCOMING)

        assertEquals(CallNotificationPolicy.INCOMING_CHANNEL_ID, spec.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, spec.channelImportance)
        assertEquals(NotificationCompat.PRIORITY_MAX, spec.notificationPriority)
        assertTrue(spec.useFullScreenIntent)
        assertFalse(spec.silent)
    }

    @Test
    fun test_ongoingCallSpec_usesSeparateLowImportanceSilentChannel() {
        val incoming = CallNotificationPolicy.specFor(CallNotificationKind.INCOMING)
        val ongoing = CallNotificationPolicy.specFor(CallNotificationKind.ONGOING)

        assertEquals(CallNotificationPolicy.ONGOING_CHANNEL_ID, ongoing.channelId)
        assertNotEquals(incoming.channelId, ongoing.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ongoing.channelImportance)
        assertTrue(ongoing.channelImportance < NotificationManager.IMPORTANCE_HIGH)
        assertEquals(NotificationCompat.PRIORITY_LOW, ongoing.notificationPriority)
        assertTrue(ongoing.notificationPriority < NotificationCompat.PRIORITY_HIGH)
        assertFalse(ongoing.useFullScreenIntent)
        assertTrue(ongoing.silent)
    }
}

