package com.example.thesis.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalingPolicyTest {

    @Test
    fun isOfferExpired_enforces45SecondTtlAndClockBounds() {
        val now = 1_760_000_100_000L
        assertFalse(CallSignalingPolicy.isOfferExpired(offerTimestampMs = now - 10_000L, nowMs = now))
        assertFalse(CallSignalingPolicy.isOfferExpired(offerTimestampMs = now - 45_000L, nowMs = now))
        assertTrue(CallSignalingPolicy.isOfferExpired(offerTimestampMs = now - 45_001L, nowMs = now))
        assertTrue(CallSignalingPolicy.isOfferExpired(offerTimestampMs = 0L, nowMs = now))
        assertTrue(CallSignalingPolicy.isOfferExpired(offerTimestampMs = now + 120_001L, nowMs = now))
    }

    @Test
    fun resolveGlare_deterministicallyBreaksTiesAcrossBothPeers() {
        val lowInbox = "0x1111aaaa"
        val highInbox = "0xFFFFBBBB"

        assertEquals(GlareResolution.KEEP_LOCAL_OFFER, CallSignalingPolicy.resolveGlare(lowInbox, highInbox))
        assertEquals(GlareResolution.YIELD_TO_REMOTE_OFFER, CallSignalingPolicy.resolveGlare(highInbox, lowInbox))
    }

    @Test
    fun shouldAutoReplyBusy_distinguishesActiveVsSameOrIdleCall() {
        assertFalse(CallSignalingPolicy.shouldAutoReplyBusy(activeCallId = null, incomingCallId = "c1"))
        assertFalse(CallSignalingPolicy.shouldAutoReplyBusy(activeCallId = "c1", incomingCallId = "c1"))
        assertTrue(CallSignalingPolicy.shouldAutoReplyBusy(activeCallId = "c1", incomingCallId = "c2"))
    }

    @Test
    fun classifyIceCandidateType_acceptsDirectP2pCandidatesOnly() {
        assertEquals(
            CallTransportType.DIRECT_P2P,
            CallSignalingPolicy.classifyIceCandidateType("candidate:1 1 udp 2122260223 192.168.1.2 5000 typ host")
        )
        assertEquals(
            CallTransportType.DIRECT_P2P,
            CallSignalingPolicy.classifyIceCandidateType("candidate:2 1 udp 1686052607 198.51.100.3 61000 typ srflx raddr 192.168.1.2")
        )
        assertEquals(
            CallTransportType.UNKNOWN,
            CallSignalingPolicy.classifyIceCandidateType("candidate:3 1 tcp 41885439 203.0.113.99 443 typ relay raddr 0.0.0.0")
        )
        assertEquals(
            CallTransportType.UNKNOWN,
            CallSignalingPolicy.classifySelectedCandidatePair("relay", "relay")
        )
        assertEquals(
            CallTransportType.DIRECT_P2P,
            CallSignalingPolicy.classifySelectedCandidatePair("srflx", "prflx")
        )
    }

    @Test
    fun iceServerConfig_remainsDecentralizedWithStatelessStunOnly() {
        val stunEndpoints = IceServerConfig.defaultStunEndpoints
        assertTrue(stunEndpoints.isNotEmpty())
        val allUrls = stunEndpoints.flatMap { it.urls }
        assertTrue(allUrls.isNotEmpty())
        // Verificamos ausencia total de servidores TURN centralizados por defecto.
        assertTrue(allUrls.all { it.startsWith("stun:") })
        assertTrue(stunEndpoints.all { it.username == null && it.credential == null })
    }

    @Test
    fun isVideoOffer_detectsVideoBySdpOrReason() {
        assertTrue(CallSignalingPolicy.isVideoOffer(sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111", reason = "video"))
        assertTrue(CallSignalingPolicy.isVideoOffer(sdp = "v=0\r\nm=audio 9...\r\nm=video 9 UDP/TLS/RTP/SAVPF 96", reason = null))
        assertFalse(CallSignalingPolicy.isVideoOffer(sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111", reason = null))
    }
}
