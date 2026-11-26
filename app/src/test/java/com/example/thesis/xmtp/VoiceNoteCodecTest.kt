package com.example.thesis.xmtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNoteCodecTest {

    @Test
    fun encodeAndDecode_roundTripsVoiceNoteWithWaveformAndAudio() {
        val sampleAudio = ByteArray(1024) { idx -> (idx % 127).toByte() }
        val note = VoiceNote(
            mimeType = "audio/mp4",
            durationMs = 14_200L,
            waveformPeaks = listOf(12, 48, 85, 33, 64, 92, 18),
            audioBytes = sampleAudio
        )

        val encoded = VoiceNoteCodec.encodeBytes(note)
        val decoded = VoiceNoteCodec.decodeBytes(encoded)

        assertEquals(note, decoded)
        assertEquals("🎤 Φωνητικό μήνυμα (0:14)", VoiceNoteCodec().fallback(decoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsEmptyAudioBytes() {
        VoiceNoteCodec.encodeBytes(
            VoiceNote(
                durationMs = 1_000L,
                audioBytes = ByteArray(0)
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsAudioExceeding512Kb() {
        VoiceNoteCodec.encodeBytes(
            VoiceNote(
                durationMs = 10_000L,
                audioBytes = ByteArray(VoiceNoteCodec.MAX_AUDIO_BYTES + 1)
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsCorruptMagic() {
        VoiceNoteCodec.decodeBytes(ByteArray(32) { 0x11 })
    }

    @Test
    fun downsampleWaveform_andFormatDuration_produceValidUiValues() {
        val downsampled = VoiceNoteCodec.downsampleWaveform((0..120).toList(), targetBars = 28)
        assertEquals(28, downsampled.size)
        assertTrue(downsampled.all { it in 10..100 })

        assertEquals("0:09", VoiceNoteCodec.formatDuration(9_000L))
        assertEquals("1:24", VoiceNoteCodec.formatDuration(84_000L))
        assertEquals("🎤 Φωνητικό μήνυμα (1:24)", VoiceNoteCodec.formatFallbackPreview(84_000L))
    }
}

