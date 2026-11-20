package com.example.thesis.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStateStoreTest {

    @Test
    fun test_codec_deduplicatesFiltersBlanksPreservesLatestOrderAndTruncates() {
        val rawIds = listOf("msg_1", "  ", "msg_2", "msg_1", "msg_3", "msg_4")
        val serialized = NotificationStateStoreCodec.serializeSeenIds(rawIds, maxSize = 3)
        assertEquals("msg_1\nmsg_3\nmsg_4", serialized)

        val deserialized = NotificationStateStoreCodec.deserializeSeenIds(serialized, maxSize = 3)
        assertEquals(listOf("msg_1", "msg_3", "msg_4"), deserialized)
        assertTrue(NotificationStateStoreCodec.deserializeSeenIds("   \n\n").isEmpty())
    }

    @Test
    fun test_inMemoryStorage_roundtripsAndClearsForGdprWipe() {
        val storage = InMemorySeenMessageStorage(maxSize = 4)
        assertFalse(storage.isBaselineEstablished())
        assertTrue(storage.loadSeenIds().isEmpty())

        storage.saveSeenIds(listOf("m1", "m2", "m1", "m3", "m4", "m5"))
        storage.setBaselineEstablished(true)

        assertTrue(storage.isBaselineEstablished())
        // Confirmamos retencion de los ultimos 4 tras reordenar el duplicado m1 al final antes de m3.
        assertEquals(listOf("m1", "m3", "m4", "m5"), storage.loadSeenIds())

        storage.clear()
        assertFalse(storage.isBaselineEstablished())
        assertTrue(storage.loadSeenIds().isEmpty())
    }
}
