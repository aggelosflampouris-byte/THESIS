package com.example.thesis.xmtp

import com.example.thesis.util.CryptoUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAttachmentCodecTest {

    @Test
    fun roundTrip_imageAttachmentPreservesAllFieldsAndSha256() {
        val samplePayload = ByteArray(4096) { idx -> (idx * 17 + 5).toByte() }
        val original = MediaAttachment(
            fileName = "field_sample.jpg",
            mimeType = "image/jpeg",
            widthPx = 1440,
            heightPx = 1080,
            caption = "Δοκιμαστική φωτογραφία MLS E2EE",
            sha256Hex = CryptoUtils.sha256Hex(samplePayload),
            dataBytes = samplePayload
        )

        val wireBytes = MediaAttachmentCodec.encodeBytes(original)
        val decoded = MediaAttachmentCodec.decodeBytes(wireBytes)

        assertEquals(original.fileName, decoded.fileName)
        assertEquals(original.mimeType, decoded.mimeType)
        assertEquals(original.widthPx, decoded.widthPx)
        assertEquals(original.heightPx, decoded.heightPx)
        assertEquals(original.caption, decoded.caption)
        assertEquals(original.resolvedSha256Hex, decoded.resolvedSha256Hex)
        assertArrayEquals(original.dataBytes, decoded.dataBytes)
    }

    @Test
    fun roundTrip_documentAttachmentWithoutDimensions() {
        val pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".toByteArray(Charsets.US_ASCII)
        val original = MediaAttachment(
            fileName = "thesis-spec.pdf",
            mimeType = "application/pdf",
            widthPx = 0,
            heightPx = 0,
            caption = "",
            dataBytes = pdfBytes
        )

        val encoded = MediaAttachmentCodec().encode(original)
        val decoded = MediaAttachmentCodec().decode(encoded)

        assertEquals("thesis-spec.pdf", decoded.fileName)
        assertEquals("application/pdf", decoded.mimeType)
        assertEquals(0, decoded.widthPx)
        assertEquals(0, decoded.heightPx)
        assertEquals("", decoded.caption)
        assertArrayEquals(pdfBytes, decoded.dataBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeBytes_rejectsOversizedPayload() {
        val oversized = ByteArray(MediaAttachmentCodec.MAX_MEDIA_BYTES + 1)
        MediaAttachmentCodec.encodeBytes(
            MediaAttachment(
                fileName = "too-large.bin",
                mimeType = "application/octet-stream",
                dataBytes = oversized
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeBytes_rejectsEmptyPayload() {
        MediaAttachmentCodec.encodeBytes(
            MediaAttachment(
                fileName = "empty.txt",
                mimeType = "text/plain",
                dataBytes = ByteArray(0)
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeBytes_rejectsTamperedPayloadBySha256() {
        val original = MediaAttachment(
            fileName = "integrity.bin",
            mimeType = "application/octet-stream",
            dataBytes = ByteArray(128) { it.toByte() }
        )
        val wireBytes = MediaAttachmentCodec.encodeBytes(original)
        // Alteramos el ultimo byte del cuerpo para disparar la comprobacion SHA-256 al decodificar.
        wireBytes[wireBytes.lastIndex] = (wireBytes[wireBytes.lastIndex].toInt() xor 0xFF).toByte()
        MediaAttachmentCodec.decodeBytes(wireBytes)
    }

    @Test
    fun sanitizeFileName_stripsTraversalAndPreservesExtension() {
        assertEquals("key.pdf", MediaAttachmentCodec.sanitizeFileName("../../secret/key.pdf"))
        assertEquals("attachment.bin", MediaAttachmentCodec.sanitizeFileName("   /\\..  "))

        val longName = "a".repeat(160) + ".docx"
        val sanitized = MediaAttachmentCodec.sanitizeFileName(longName)
        assertTrue(sanitized.length <= MediaAttachmentCodec.MAX_FILENAME_CHARS)
        assertTrue(sanitized.endsWith(".docx"))
    }

    @Test
    fun formattingHelpers_distinguishImagesAndDocuments() {
        assertTrue(MediaAttachmentCodec.isImageMimeType("image/jpeg"))
        assertFalse(MediaAttachmentCodec.isImageMimeType("image/svg+xml"))
        assertFalse(MediaAttachmentCodec.isImageMimeType("application/pdf"))

        assertEquals("512 B", MediaAttachmentCodec.formatByteSize(512))
        assertEquals("2.0 KB", MediaAttachmentCodec.formatByteSize(2048))

        val imgPreview = MediaAttachmentCodec.formatFallbackPreview(
            MediaAttachment(fileName = "a.jpg", mimeType = "image/jpeg", dataBytes = ByteArray(2048), caption = "Hi")
        )
        assertEquals("📷 Εικόνα (2.0 KB) — Hi", imgPreview)

        val filePreview = MediaAttachmentCodec.formatFallbackPreview(
            MediaAttachment(fileName = "doc.pdf", mimeType = "application/pdf", dataBytes = ByteArray(1024))
        )
        assertEquals("📎 Αρχείο: doc.pdf (1.0 KB)", filePreview)
    }
}

