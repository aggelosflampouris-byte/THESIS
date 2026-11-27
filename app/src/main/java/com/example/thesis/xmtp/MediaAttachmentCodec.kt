package com.example.thesis.xmtp

import com.example.thesis.util.CryptoUtils
import com.google.protobuf.ByteString
import org.xmtp.android.library.codecs.ContentCodec
import org.xmtp.android.library.codecs.ContentTypeIdBuilder
import org.xmtp.proto.message.contents.Content.ContentTypeId
import org.xmtp.proto.message.contents.Content.EncodedContent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

val ContentTypeMediaAttachment: ContentTypeId = ContentTypeIdBuilder.builderFromAuthorityId(
    authorityId = "thesis.xmtp.app",
    typeId = "media_attachment",
    versionMajor = 1,
    versionMinor = 0
)

/**
 * Adjunto multimedia cifrado E2EE en el canal MLS sin servidores de almacenamiento externos.
 */
data class MediaAttachment(
    val version: Int = MediaAttachmentCodec.WIRE_VERSION,
    val fileName: String,
    val mimeType: String,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val caption: String = "",
    val sha256Hex: String = "",
    val dataBytes: ByteArray
) {
    val resolvedSha256Hex: String
        get() = if (sha256Hex.isNotBlank()) sha256Hex else CryptoUtils.sha256Hex(dataBytes)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MediaAttachment
        return version == other.version &&
            fileName == other.fileName &&
            mimeType == other.mimeType &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            caption == other.caption &&
            resolvedSha256Hex == other.resolvedSha256Hex &&
            dataBytes.contentEquals(other.dataBytes)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + caption.hashCode()
        result = 31 * result + resolvedSha256Hex.hashCode()
        result = 31 * result + dataBytes.contentHashCode()
        return result
    }
}

// Codec binario puro para JVM que transporta fotos y documentos verificados por SHA-256 dentro de MLS sin servidor propio.
class MediaAttachmentCodec : ContentCodec<MediaAttachment> {
    override val contentType: ContentTypeId = ContentTypeMediaAttachment

    override fun encode(content: MediaAttachment): EncodedContent {
        val raw = encodeBytes(content)
        return EncodedContent.newBuilder()
            .setType(contentType)
            .setContent(ByteString.copyFrom(raw))
            .build()
    }

    override fun decode(content: EncodedContent): MediaAttachment {
        return decodeBytes(content.content.toByteArray())
    }

    override fun fallback(content: MediaAttachment): String = formatFallbackPreview(content)

    override fun shouldPush(content: MediaAttachment): Boolean = true

    companion object {
        private const val MAGIC_0: Byte = 0x54 // 'T'
        private const val MAGIC_1: Byte = 0x4D // 'M'
        const val WIRE_VERSION: Int = 1
        const val MAX_MEDIA_BYTES: Int = 768 * 1024
        const val MAX_FILENAME_CHARS: Int = 120
        private const val MAX_FILENAME_BYTES: Int = 256
        private const val MAX_MIME_BYTES: Int = 128
        private const val MAX_CAPTION_BYTES: Int = 1000
        const val MAX_CAPTION_CHARS: Int = 300
        const val MAX_DIMENSION_PX: Int = 8192

        fun encodeBytes(attachment: MediaAttachment): ByteArray {
            require(attachment.version == WIRE_VERSION) {
                "Unsupported MediaAttachment wire version: ${attachment.version}"
            }
            val cleanName = sanitizeFileName(attachment.fileName)
            val cleanMime = attachment.mimeType.trim()
            require(cleanMime.isNotBlank()) { "MediaAttachment mimeType cannot be blank" }
            require(attachment.widthPx in 0..MAX_DIMENSION_PX) {
                "Invalid widthPx: ${attachment.widthPx}"
            }
            require(attachment.heightPx in 0..MAX_DIMENSION_PX) {
                "Invalid heightPx: ${attachment.heightPx}"
            }
            require(attachment.dataBytes.isNotEmpty()) { "MediaAttachment dataBytes cannot be empty" }
            require(attachment.dataBytes.size <= MAX_MEDIA_BYTES) {
                "MediaAttachment dataBytes exceeds $MAX_MEDIA_BYTES bytes: ${attachment.dataBytes.size}"
            }

            val nameBytes = cleanName.toByteArray(StandardCharsets.UTF_8)
            require(nameBytes.size in 1..MAX_FILENAME_BYTES) {
                "fileName exceeds $MAX_FILENAME_BYTES UTF-8 bytes"
            }
            val mimeBytes = cleanMime.toByteArray(StandardCharsets.UTF_8)
            require(mimeBytes.size in 1..MAX_MIME_BYTES) {
                "mimeType exceeds $MAX_MIME_BYTES UTF-8 bytes"
            }

            val cleanCaption = attachment.caption.trim().take(MAX_CAPTION_CHARS)
            val captionBytes = cleanCaption.toByteArray(StandardCharsets.UTF_8)
            require(captionBytes.size <= MAX_CAPTION_BYTES) {
                "caption exceeds $MAX_CAPTION_BYTES UTF-8 bytes"
            }

            // Calculamos y validamos la huella SHA-256 del contenido para verificar integridad en destino.
            val computedSha = CryptoUtils.sha256Hex(attachment.dataBytes)
            val expectedSha = attachment.sha256Hex.trim().lowercase(Locale.ROOT)
            require(expectedSha.isEmpty() || expectedSha == computedSha) {
                "MediaAttachment SHA-256 mismatch before encoding"
            }
            val shaBytes = computedSha.toByteArray(StandardCharsets.US_ASCII)

            val baos = ByteArrayOutputStream(128 + attachment.dataBytes.size)
            DataOutputStream(baos).use { out ->
                out.writeByte(MAGIC_0.toInt())
                out.writeByte(MAGIC_1.toInt())
                out.writeInt(attachment.version)
                out.writeInt(nameBytes.size)
                out.write(nameBytes)
                out.writeInt(mimeBytes.size)
                out.write(mimeBytes)
                out.writeInt(attachment.widthPx)
                out.writeInt(attachment.heightPx)
                out.writeInt(captionBytes.size)
                if (captionBytes.isNotEmpty()) {
                    out.write(captionBytes)
                }
                out.writeInt(shaBytes.size)
                out.write(shaBytes)
                out.writeInt(attachment.dataBytes.size)
                out.write(attachment.dataBytes)
                out.flush()
            }
            return baos.toByteArray()
        }

        fun decodeBytes(bytes: ByteArray): MediaAttachment {
            require(bytes.size >= 34) { "Payload too short for MediaAttachment" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val m0 = input.readByte()
                val m1 = input.readByte()
                require(m0 == MAGIC_0 && m1 == MAGIC_1) { "Invalid MediaAttachment header magic" }

                val version = input.readInt()
                require(version == WIRE_VERSION) { "Unsupported MediaAttachment wire version: $version" }

                val nameLen = input.readInt()
                require(nameLen in 1..MAX_FILENAME_BYTES) { "Invalid fileName length: $nameLen" }
                val nameBuf = ByteArray(nameLen)
                input.readFully(nameBuf)
                val fileName = sanitizeFileName(String(nameBuf, StandardCharsets.UTF_8))

                val mimeLen = input.readInt()
                require(mimeLen in 1..MAX_MIME_BYTES) { "Invalid mimeType length: $mimeLen" }
                val mimeBuf = ByteArray(mimeLen)
                input.readFully(mimeBuf)
                val mimeType = String(mimeBuf, StandardCharsets.UTF_8).trim()
                require(mimeType.isNotBlank()) { "Decoded mimeType cannot be blank" }

                val widthPx = input.readInt()
                val heightPx = input.readInt()
                require(widthPx in 0..MAX_DIMENSION_PX && heightPx in 0..MAX_DIMENSION_PX) {
                    "Invalid decoded dimensions: ${widthPx}x$heightPx"
                }

                val captionLen = input.readInt()
                require(captionLen in 0..MAX_CAPTION_BYTES) { "Invalid caption length: $captionLen" }
                val caption = if (captionLen == 0) {
                    ""
                } else {
                    val captionBuf = ByteArray(captionLen)
                    input.readFully(captionBuf)
                    String(captionBuf, StandardCharsets.UTF_8)
                }

                val shaLen = input.readInt()
                require(shaLen == 64) { "Invalid SHA-256 hex length: $shaLen" }
                val shaBuf = ByteArray(shaLen)
                input.readFully(shaBuf)
                val shaHex = String(shaBuf, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)

                val dataLen = input.readInt()
                require(dataLen in 1..MAX_MEDIA_BYTES) { "Invalid dataBytes length: $dataLen" }
                val dataBytes = ByteArray(dataLen)
                input.readFully(dataBytes)

                val recomputedSha = CryptoUtils.sha256Hex(dataBytes)
                require(recomputedSha == shaHex) { "MediaAttachment SHA-256 verification failed" }

                return MediaAttachment(
                    version = version,
                    fileName = fileName,
                    mimeType = mimeType,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    caption = caption,
                    sha256Hex = shaHex,
                    dataBytes = dataBytes
                )
            }
        }

        // Eliminamos rutas relativas, caracteres de control y simbolos no seguros conservando la extension.
        fun sanitizeFileName(rawName: String?, fallback: String = "attachment.bin"): String {
            val baseOnly = rawName
                .orEmpty()
                .replace('\u0000', '_')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace("..", "_")
                .replace(Regex("[:*?\"<>|\\p{Cntrl}]"), "_")
                .trim()
                .trimStart('.')
            if (baseOnly.isBlank() || baseOnly.all { it == '_' || it == '.' }) return fallback
            if (baseOnly.length <= MAX_FILENAME_CHARS) return baseOnly

            val dotIdx = baseOnly.lastIndexOf('.')
            return if (dotIdx in 1 until (baseOnly.length - 1)) {
                val ext = baseOnly.substring(dotIdx).take(16)
                val maxBase = (MAX_FILENAME_CHARS - ext.length).coerceAtLeast(1)
                baseOnly.substring(0, maxBase) + ext
            } else {
                baseOnly.take(MAX_FILENAME_CHARS)
            }
        }

        fun isImageMimeType(mimeType: String): Boolean {
            val normalized = mimeType.trim().lowercase(Locale.ROOT)
            return normalized.startsWith("image/") && normalized != "image/svg+xml"
        }

        fun formatByteSize(bytes: Int): String {
            val safe = bytes.coerceAtLeast(0)
            return when {
                safe < 1024 -> "$safe B"
                safe < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
                else -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
            }
        }

        fun formatFallbackPreview(attachment: MediaAttachment): String {
            val sizeLabel = formatByteSize(attachment.dataBytes.size)
            val suffix = if (attachment.caption.isNotBlank()) " — ${attachment.caption}" else ""
            return if (isImageMimeType(attachment.mimeType)) {
                "📷 Εικόνα ($sizeLabel)$suffix"
            } else {
                "📎 Αρχείο: ${attachment.fileName} ($sizeLabel)$suffix"
            }
        }
    }
}

