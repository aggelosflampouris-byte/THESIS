package com.example.thesis.call

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class GlareResolution {
    KEEP_LOCAL_OFFER,
    YIELD_TO_REMOTE_OFFER
}

enum class CallTransportType {
    DIRECT_P2P,
    RELAYED_TURN,
    UNKNOWN
}

// Reglas puras de JVM para senalizacion de llamadas: TTL, desempate de glare, SAS DTLS y clasificacion ICE.
object CallSignalingPolicy {
    const val OFFER_TTL_MS: Long = 45_000L
    const val MAX_FUTURE_SKEW_MS: Long = 120_000L
    const val RING_TIMEOUT_MS: Long = 45_000L
    const val CONNECT_TIMEOUT_MS: Long = 20_000L
    const val ICE_BATCH_DEBOUNCE_MS: Long = 300L

    private val DTLS_SHA256_REGEX = Regex(
        pattern = """(?im)^a=fingerprint:sha-256\s+([0-9a-f]{2}(?::[0-9a-f]{2})+)\s*$"""
    )

    private val ICE_CAND_TYPE_REGEX = Regex(
        pattern = """\btyp\s+(host|srflx|prflx|relay)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

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

    fun extractDtlsSha256Fingerprint(sdp: String?): String? {
        if (sdp.isNullOrBlank()) return null
        val match = DTLS_SHA256_REGEX.find(sdp) ?: return null
        return normalizeFingerprint(match.groupValues[1])
    }

    fun deriveVoiceSasCode(
        callId: String,
        localFingerprint: String?,
        remoteFingerprint: String?,
        localInboxId: String,
        remoteInboxId: String
    ): String? {
        val cleanCallId = callId.trim()
        val normLocalFp = normalizeFingerprint(localFingerprint)
        val normRemoteFp = normalizeFingerprint(remoteFingerprint)
        val normLocalInbox = localInboxId.trim().lowercase(Locale.ROOT)
        val normRemoteInbox = remoteInboxId.trim().lowercase(Locale.ROOT)
        if (cleanCallId.isEmpty() || normLocalFp == null || normRemoteFp == null) return null
        if (normLocalInbox.isEmpty() || normRemoteInbox.isEmpty()) return null

        // Ordenamos lexicograficamente los pares (inboxId:fingerprint) para garantizar codigo simetrico en ambos extremos.
        val firstTuple = "$normLocalInbox:$normLocalFp"
        val secondTuple = "$normRemoteInbox:$normRemoteFp"
        val ordered = listOf(firstTuple, secondTuple).sorted()

        val material = "THESIS-CALL-SAS-V1|$cleanCallId|${ordered[0]}|${ordered[1]}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))

        val unsignedInt = ((digest[0].toInt() and 0x7F) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        val sixDigits = unsignedInt % 1_000_000
        return String.format(Locale.US, "%06d", sixDigits)
    }

    fun classifyIceCandidateType(candidateSdp: String?): CallTransportType {
        if (candidateSdp.isNullOrBlank()) return CallTransportType.UNKNOWN
        val match = ICE_CAND_TYPE_REGEX.find(candidateSdp) ?: return CallTransportType.UNKNOWN
        return when (match.groupValues[1].lowercase(Locale.ROOT)) {
            "relay" -> CallTransportType.RELAYED_TURN
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
        if (local == "relay" || remote == "relay") return CallTransportType.RELAYED_TURN
        if (local.isNotEmpty() || remote.isNotEmpty()) return CallTransportType.DIRECT_P2P
        return CallTransportType.UNKNOWN
    }

    private fun normalizeFingerprint(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().lowercase(Locale.ROOT)
        if (!cleaned.contains(':')) return null
        return cleaned
    }
}
