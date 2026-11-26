package com.example.thesis.xmtp

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
import kotlin.math.roundToInt

val ContentTypeVoiceNote: ContentTypeId = ContentTypeIdBuilder.builderFromAuthorityId(
    authorityId = "thesis.xmtp.app",
    typeId = "voice_note",
    versionMajor = 1,
    versionMinor = 0
)

data class VoiceNote(
    val version: Int = VoiceNoteCodec.WIRE_VERSION,
    val mimeType: String = VoiceNoteCodec.DEFAULT_MIME_TYPE,
    val durationMs: Long,
    val waveformPeaks: List<Int> = emptyList(),
    val audioBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoiceNote
        return version == other.version &&
            mimeType == other.mimeType &&
            durationMs == other.durationMs &&
            waveformPeaks == other.waveformPeaks &&
            audioBytes.contentEquals(other.audioBytes)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + waveformPeaks.hashCode()
        result = 31 * result + audioBytes.contentHashCode()
        return result
    }
}

// Codec binario puro para JVM que transporta mensajes de voz cifrados E2EE dentro de la envolvente MLS sin servidor multimedia.
class VoiceNoteCodec : ContentCodec<VoiceNote> {
    override val contentType: ContentTypeId = ContentTypeVoiceNote

    override fun encode(content: VoiceNote): EncodedContent {
        val raw = encodeBytes(content)
        return EncodedContent.newBuilder()
            .setType(contentType)
            .setContent(ByteString.copyFrom(raw))
            .build()
    }

    override fun decode(content: EncodedContent): VoiceNote {
        return decodeBytes(content.content.toByteArray())
    }

    override fun fallback(content: VoiceNote): String = formatFallbackPreview(content.durationMs)

    override fun shouldPush(content: VoiceNote): Boolean = true

    companion object {
        private const val MAGIC_0: Byte = 0x54 // 'T'
        private const val MAGIC_1: Byte = 0x56 // 'V'
        const val WIRE_VERSION: Int = 1
        const val DEFAULT_MIME_TYPE: String = "audio/mp4"
        const val MAX_AUDIO_BYTES: Int = 512 * 1024
        const val MAX_DURATION_MS: Long = 120_000L
        const val MAX_WAVEFORM_BARS: Int = 48
        private const val MAX_MIME_BYTES: Int = 128

        fun encodeBytes(note: VoiceNote): ByteArray {
            require(note.mimeType.isNotBlank()) { "mimeType cannot be blank" }
            require(note.durationMs in 1L..MAX_DURATION_MS) {
                "Invalid voice note durationMs: ${note.durationMs}"
            }
            require(note.audioBytes.isNotEmpty()) { "VoiceNote audioBytes cannot be empty" }
            require(note.audioBytes.size <= MAX_AUDIO_BYTES) {
                "VoiceNote audioBytes exceeds $MAX_AUDIO_BYTES bytes: ${note.audioBytes.size}"
            }
            val clampedPeaks = note.waveformPeaks.take(MAX_WAVEFORM_BARS).map { it.coerceIn(0, 100) }
            val mimeBytes = note.mimeType.toByteArray(StandardCharsets.UTF_8)
            require(mimeBytes.size <= MAX_MIME_BYTES) { "mimeType exceeds $MAX_MIME_BYTES bytes" }

            val baos = ByteArrayOutputStream(64 + note.audioBytes.size)
            DataOutputStream(baos).use { out ->
                out.writeByte(MAGIC_0.toInt())
                out.writeByte(MAGIC_1.toInt())
                out.writeInt(note.version)
                out.writeInt(mimeBytes.size)
                out.write(mimeBytes)
                out.writeLong(note.durationMs)
                out.writeInt(clampedPeaks.size)
                for (peak in clampedPeaks) {
                    out.writeByte(peak)
                }
                out.writeInt(note.audioBytes.size)
                out.write(note.audioBytes)
                out.flush()
            }
            return baos.toByteArray()
        }

        fun decodeBytes(bytes: ByteArray): VoiceNote {
            require(bytes.size >= 22) { "Payload too short for VoiceNote" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val m0 = input.readByte()
                val m1 = input.readByte()
                require(m0 == MAGIC_0 && m1 == MAGIC_1) { "Invalid VoiceNote header magic" }

                val version = input.readInt()
                require(version == WIRE_VERSION) { "Unsupported VoiceNote wire version: $version" }

                val mimeLen = input.readInt()
                require(mimeLen in 1..MAX_MIME_BYTES) { "Invalid mimeType length: $mimeLen" }
                val mimeBuf = ByteArray(mimeLen)
                input.readFully(mimeBuf)
                val mimeType = String(mimeBuf, StandardCharsets.UTF_8)

                val durationMs = input.readLong()
                require(durationMs in 1L..MAX_DURATION_MS) {
                    "Invalid decoded VoiceNote durationMs: $durationMs"
                }

                val peakCount = input.readInt()
                require(peakCount in 0..MAX_WAVEFORM_BARS) { "Invalid waveform peakCount: $peakCount" }
                val peaks = if (peakCount == 0) {
                    emptyList()
                } else {
                    List(peakCount) { input.readUnsignedByte().coerceIn(0, 100) }
                }

                val audioLen = input.readInt()
                require(audioLen in 1..MAX_AUDIO_BYTES) { "Invalid VoiceNote audioLen: $audioLen" }
                val audioBytes = ByteArray(audioLen)
                input.readFully(audioBytes)

                return VoiceNote(
                    version = version,
                    mimeType = mimeType,
                    durationMs = durationMs,
                    waveformPeaks = peaks,
                    audioBytes = audioBytes
                )
            }
        }

        fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs.coerceAtLeast(0L) + 500L) / 1_000L
            val clampedSeconds = if (durationMs > 0L) totalSeconds.coerceAtLeast(1L) else 0L
            val mins = clampedSeconds / 60L
            val secs = clampedSeconds % 60L
            return String.format(Locale.US, "%d:%02d", mins, secs)
        }

        fun formatFallbackPreview(durationMs: Long): String =
            "🎤 Φωνητικό μήνυμα (${formatDuration(durationMs)})"

        fun downsampleWaveform(samples: List<Int>, targetBars: Int = 28): List<Int> {
            val safeTarget = targetBars.coerceIn(8, MAX_WAVEFORM_BARS)
            if (samples.isEmpty()) {
                return List(safeTarget) { idx -> if (idx % 3 == 0) 45 else 24 }
            }
            if (samples.size <= safeTarget) {
                return List(safeTarget) { idx ->
                    val mapped = (idx * samples.size) / safeTarget
                    samples[mapped].coerceIn(10, 100)
                }
            }
            return List(safeTarget) { barIdx ->
                val start = (barIdx * samples.size) / safeTarget
                val end = (((barIdx + 1) * samples.size) / safeTarget).coerceAtLeast(start + 1)
                var sum = 0
                var count = 0
                for (i in start until end.coerceAtMost(samples.size)) {
                    sum += samples[i].coerceIn(0, 100)
                    count++
                }
                val avg = if (count > 0) (sum.toFloat() / count).roundToInt() else 10
                avg.coerceIn(10, 100)
            }
        }
    }
}

