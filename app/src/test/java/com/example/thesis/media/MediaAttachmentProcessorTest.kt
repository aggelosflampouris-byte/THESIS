package com.example.thesis.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MediaAttachmentProcessorTest {

    @Test
    fun test_readImageBounds_succeedsWhenStreamOpensAndBoundsPopulated() {
        var streamClosed = false
        val trackingStream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() {
                streamClosed = true
                super.close()
            }
        }

        // Simulamos decodeStream(..., inJustDecodeBounds = true): lee el flujo y rellena dimensiones.
        val bounds = MediaAttachmentProcessor.readImageBounds(
            openStream = { trackingStream },
            decodeBounds = { stream ->
                assertTrue(stream.read() >= 0)
                1920 to 1080
            }
        )

        assertEquals(1920 to 1080, bounds)
        assertTrue(streamClosed)
    }

    @Test
    fun test_readImageBounds_throwsWhenOpenStreamReturnsNull() {
        val ex = assertThrows(IllegalStateException::class.java) {
            MediaAttachmentProcessor.readImageBounds(
                openStream = { null },
                decodeBounds = { 100 to 100 }
            )
        }

        assertEquals("Αδύνατη η ανάγνωση της επιλεγμένης εικόνας.", ex.message)
    }

    @Test
    fun test_readImageBounds_throwsOnNonPositiveDimensions() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            MediaAttachmentProcessor.readImageBounds(
                openStream = { ByteArrayInputStream(byteArrayOf(0)) },
                decodeBounds = { -1 to -1 }
            )
        }

        assertEquals("Μη έγκυρη ή κατεστραμμένη μορφή εικόνας.", ex.message)
    }

    @Test
    fun test_computeInSampleSize_downscalesLargeImagesToPowerOfTwo() {
        assertEquals(1, MediaAttachmentProcessor.computeInSampleSize(800, 600, 1440))
        assertEquals(2, MediaAttachmentProcessor.computeInSampleSize(2880, 2160, 1440))
        assertEquals(4, MediaAttachmentProcessor.computeInSampleSize(6000, 4000, 1440))
    }
}

