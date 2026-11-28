package com.example.thesis.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaViewLimitTest {

    @Test
    fun test_policyCalculationsAndLabels() {
        assertEquals(0, MediaViewLimitPolicy.sanitizeMaxViews(-5))
        assertEquals(2, MediaViewLimitPolicy.DEFAULT_TEMPORARY_VIEWS)
        assertEquals(2, MediaViewLimitPolicy.sanitizeMaxViews(2))
        assertEquals(MediaViewLimitPolicy.MAX_VIEW_LIMIT, MediaViewLimitPolicy.sanitizeMaxViews(99))

        assertEquals(Int.MAX_VALUE, MediaViewLimitPolicy.remainingViews(0, 5))
        assertFalse(MediaViewLimitPolicy.isExpired(0, 5))
        assertTrue(MediaViewLimitPolicy.canOpen(0, 5))

        assertEquals(2, MediaViewLimitPolicy.remainingViews(2, 0))
        assertEquals(1, MediaViewLimitPolicy.remainingViews(2, 1))
        assertEquals(0, MediaViewLimitPolicy.remainingViews(2, 2))
        assertFalse(MediaViewLimitPolicy.isExpired(2, 1))
        assertTrue(MediaViewLimitPolicy.isExpired(2, 2))
        assertFalse(MediaViewLimitPolicy.canOpen(2, 2))

        assertEquals("Κανονικό", MediaViewLimitPolicy.formatPresetLabel(0))
        assertEquals("2×", MediaViewLimitPolicy.formatPresetLabel(2))
        assertEquals("Απομένουν 2/2 προβολές", MediaViewLimitPolicy.formatRemainingSubtitle(2, 0))
        assertEquals("Απομένει 1/2 προβολή", MediaViewLimitPolicy.formatRemainingSubtitle(2, 1))
        assertEquals("Έληξε • Ανοίχτηκε 1/1 φορά", MediaViewLimitPolicy.formatExpiredSubtitle(1))
        assertEquals("Έληξε • Ανοίχτηκε 2/2 φορές", MediaViewLimitPolicy.formatExpiredSubtitle(2))
        assertEquals(
            "Προσωρινό μέσο • Προβολή 1/2 • Προστασία οθόνης",
            MediaViewLimitPolicy.formatViewerBanner(1, 2)
        )
        assertEquals(
            "Τελευταία προβολή (2/2) • Προστασία οθόνης",
            MediaViewLimitPolicy.formatViewerBanner(2, 2)
        )
    }

    @Test
    fun test_codecRoundTripAndMalformedResilience() {
        val original = mapOf(
            "msg_b" to 2,
            "msg_a" to 1,
            "ignored_zero" to 0,
            "" to 3
        )
        val encoded = MediaViewLimitCodec.serializeMap(original)
        val decoded = MediaViewLimitCodec.deserializeMap(encoded + "\nbad_line\nkey\tNaN\nneg\t-2")

        assertEquals(mapOf("msg_a" to 1, "msg_b" to 2), decoded)
        assertTrue(MediaViewLimitCodec.deserializeMap(null).isEmpty())
        assertTrue(MediaViewLimitCodec.deserializeMap("   ").isEmpty())
    }

    @Test
    fun test_inMemoryStoreEnforcesTwoViewLimitAndClearsOnShred() {
        val store = InMemoryMediaViewLimitStore()

        // Una foto temporal con limite 2 solo puede abrirse 2 veces y expira en el 3er intento.
        assertEquals(1, store.recordView("msg_2x", maxViews = 2))
        assertEquals(1, store.openedCount("msg_2x"))
        assertEquals(2, store.recordView("msg_2x", maxViews = 2))
        assertEquals(2, store.openedCount("msg_2x"))
        assertNull(store.recordView("msg_2x", maxViews = 2))
        assertEquals(2, store.openedCount("msg_2x"))

        assertNull(store.recordView("msg_unlimited", maxViews = 0))
        assertNull(store.recordView("", maxViews = 2))

        store.clear()
        assertEquals(0, store.openedCount("msg_2x"))
        assertTrue(store.openedCounts.value.isEmpty())
    }
}

