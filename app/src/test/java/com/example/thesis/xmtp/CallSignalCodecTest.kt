package com.example.thesis.xmtp

import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.xmtp.proto.message.contents.Content.EncodedContent

class CallSignalCodecTest {

    private val codec = CallSignalCodec()

    @Test
    fun roundTrip_allSignalTypes_preserveFieldsAndSuppressPushFallback() {
        val samples = listOf(
            CallSignal(
                callId = "call-offer-1",
                type = CallSignalType.OFFER,
                timestampMs = 1_760_000_000_123L,
                sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\na=fingerprint:sha-256 AA:BB:CC"
            ),
            CallSignal(
                callId = "call-answer-2",
                type = CallSignalType.ANSWER,
                timestampMs = 1_760_000_001_000L,
                sdp = "v=0\r\na=sendrecv"
            ),
            CallSignal(
                callId = "call-ice-3",
                type = CallSignalType.ICE_BATCH,
                timestampMs = 1_760_000_002_000L,
                iceCandidates = listOf(
                    CallIceCandidate("0", 0, "candidate:1 1 udp 2122260223 192.168.1.10 54321 typ host"),
                    CallIceCandidate("0", 0, "candidate:2 1 udp 1686052607 203.0.113.5 60000 typ srflx")
                )
            ),
            CallSignal(callId = "call-hangup-4", type = CallSignalType.HANGUP, timestampMs = 1_760_000_003_000L, reason = "hangup"),
            CallSignal(callId = "call-reject-5", type = CallSignalType.REJECT, timestampMs = 1_760_000_004_000L, reason = "declined"),
            CallSignal(callId = "call-busy-6", type = CallSignalType.BUSY, timestampMs = 1_760_000_005_000L, reason = "busy")
        )

        for (signal in samples) {
            val encoded = codec.encode(signal)
            assertEquals(ContentTypeCallSignal, encoded.type)
            assertFalse(codec.shouldPush(signal))
            assertNull(codec.fallback(signal))

            val decoded = codec.decode(encoded)
            assertEquals(signal, decoded)
        }
    }

    @Test
    fun roundTrip_largeSdpOver64KiB_succeedsWithoutUtfLimitError() {
        val largeSdp = "v=0\r\n" + "a=fmtp:111 minptime=10;useinbandfec=1\r\n".repeat(2_000)
        require(largeSdp.toByteArray().size > 65_535)
        val signal = CallSignal(
            callId = "call-large-sdp",
            type = CallSignalType.OFFER,
            timestampMs = 1_760_000_010_000L,
            sdp = largeSdp
        )
        val decoded = codec.decode(codec.encode(signal))
        assertEquals(signal, decoded)
    }

    @Test
    fun decode_rejectsMalformedPayloadAndBadMagic() {
        val badMagic = EncodedContent.newBuilder()
            .setType(ContentTypeCallSignal)
            .setContent(ByteString.copyFrom(ByteArray(32) { 0x01 }))
            .build()
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(badMagic)
        }

        val tooShort = EncodedContent.newBuilder()
            .setType(ContentTypeCallSignal)
            .setContent(ByteString.copyFrom(byteArrayOf(0x54, 0x43)))
            .build()
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(tooShort)
        }
    }
}
