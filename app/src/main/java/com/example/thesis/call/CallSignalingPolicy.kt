package com.example.thesis.call

import java.util.Locale

enum class GlareResolution {
    KEEP_LOCAL_OFFER,
    YIELD_TO_REMOTE_OFFER
}

enum class CallTransportType {
    DIRECT_P2P,
    UNKNOWN
}

// Reglas puras de JVM para senalizacion de llamadas: TTL, desempate de glare y clasificacion ICE.
object CallSignalingPolicy {
    const val OFFER_TTL_MS: Long = 45_000L
    const val MAX_FUTURE_SKEW_MS: Long = 120_000L
    const val RING_TIMEOUT_MS: Long = 45_000L
    // Ampliamos el margen de conexion a 30s para dar tiempo al handshake ICE/DTLS-SRTP en 4G/5G.
    const val CONNECT_TIMEOUT_MS: Long = 30_000L
    const val ICE_BATCH_DEBOUNCE_MS: Long = 300L

    private val ICE_CAND_TYPE_REGEX = Regex(
        pattern = """\btyp\s+(host|srflx|prflx|relay)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val SDP_VIDEO_MEDIA_REGEX = Regex(
        pattern = """(?im)^m=video\b"""
    )

    fun isVideoOffer(sdp: String?, reason: String? = null): Boolean {
        if (reason.equals("video", ignoreCase = true)) return true
        return !sdp.isNullOrBlank() && SDP_VIDEO_MEDIA_REGEX.containsMatchIn(sdp)
    }

    fun isOfferExpired(
        offerTimestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = OFFER_TTL_MS
    ): Boolean {
        if (offerTimestampMs <= 0L) return true
        val ageMs = nowMs - offerTimestampMs
        if (ageMs > ttlMs) return true
        // Rechazamos ofertas con marcas horarias anormalmente adelantadas frente al reloj local.
        if (offerTimestampMs - nowMs > MAX_FUTURE_SKEW_MS) return true
        return false
    }

    fun resolveGlare(localInboxId: String, remoteInboxId: String): GlareResolution {
        val local = localInboxId.trim().lowercase(Locale.ROOT)
        val remote = remoteInboxId.trim().lowercase(Locale.ROOT)
        return if (local < remote) {
            GlareResolution.KEEP_LOCAL_OFFER
        } else {
            GlareResolution.YIELD_TO_REMOTE_OFFER
        }
    }

    fun shouldAutoReplyBusy(
        activeCallId: String?,
        incomingCallId: String
    ): Boolean {
        if (activeCallId.isNullOrBlank()) return false
        return activeCallId != incomingCallId
    }

    fun classifyIceCandidateType(candidateSdp: String?): CallTransportType {
        if (candidateSdp.isNullOrBlank()) return CallTransportType.UNKNOWN
        val match = ICE_CAND_TYPE_REGEX.find(candidateSdp) ?: return CallTransportType.UNKNOWN
        return when (match.groupValues[1].lowercase(Locale.ROOT)) {
            "host", "srflx", "prflx" -> CallTransportType.DIRECT_P2P
            else -> CallTransportType.UNKNOWN
        }
    }

    fun classifySelectedCandidatePair(
        localCandidateType: String?,
        remoteCandidateType: String?
    ): CallTransportType {
        val local = localCandidateType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val remote = remoteCandidateType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val isDirect = { kind: String -> kind == "host" || kind == "srflx" || kind == "prflx" }
        if (isDirect(local) || isDirect(remote)) return CallTransportType.DIRECT_P2P
        return CallTransportType.UNKNOWN
    }
}
