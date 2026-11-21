package com.example.thesis.call

import com.example.thesis.xmtp.CallSignal
import com.example.thesis.xmtp.CallSignalType
import java.util.Locale

enum class ChatCallOutcome {
    RINGING,
    IN_PROGRESS,
    COMPLETED,
    DECLINED,
    MISSED,
    BUSY
}

data class DecodedCallSignalRecord(
    val messageId: String,
    val conversationId: String,
    val senderInboxId: String,
    val timestampMs: Long,
    val signal: CallSignal
)

data class ChatCallEvent(
    val callId: String,
    val conversationId: String,
    val callerInboxId: String,
    val isOutgoing: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSeconds: Long?,
    val outcome: ChatCallOutcome,
    val endedByLocal: Boolean
)

// Sintetiza eventos de llamada para la linea de tiempo del chat a partir de las tramas CallSignal de la conversacion.
internal object CallHistoryPolicy {
    private const val DURATION_TOKEN = ":dur="
    private const val STALE_IN_PROGRESS_TIMEOUT_MS = 2L * 60L * 60L * 1_000L

    fun encodeHangupReason(baseReason: String, durationSeconds: Long?): String {
        val cleanBase = baseReason.ifBlank { "hangup" }
        val validDuration = durationSeconds?.takeIf { it > 0L } ?: return cleanBase
        return "$cleanBase$DURATION_TOKEN$validDuration"
    }

    fun parseDurationSecondsFromReason(reason: String?): Long? {
        if (reason.isNullOrBlank()) return null
        val idx = reason.indexOf(DURATION_TOKEN)
        if (idx < 0) return null
        val raw = reason.substring(idx + DURATION_TOKEN.length).trim()
        return raw.toLongOrNull()?.takeIf { it > 0L }
    }

    fun buildCallEvents(
        records: Collection<DecodedCallSignalRecord>,
        localInboxId: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<ChatCallEvent> {
        if (records.isEmpty()) return emptyList()

        val byCallId = LinkedHashMap<String, MutableList<DecodedCallSignalRecord>>()
        for (rec in records.sortedBy { it.timestampMs }) {
            val cid = rec.signal.callId
            if (cid.isBlank()) continue
            byCallId.getOrPut(cid) { mutableListOf() }.add(rec)
        }

        val events = ArrayList<ChatCallEvent>(byCallId.size)
        for ((callId, group) in byCallId) {
            val offer = group.firstOrNull { it.signal.type == CallSignalType.OFFER }
            val answer = group.firstOrNull { it.signal.type == CallSignalType.ANSWER }
            val terminal = group.lastOrNull {
                it.signal.type == CallSignalType.HANGUP ||
                    it.signal.type == CallSignalType.REJECT ||
                    it.signal.type == CallSignalType.BUSY
            }
            if (offer == null && answer == null && terminal == null) continue

            val firstRecord = offer ?: group.first()
            val callerInboxId = firstRecord.senderInboxId
            val isOutgoing = localInboxId.isNotBlank() &&
                callerInboxId.equals(localInboxId, ignoreCase = true)
            val startedAtMs = firstRecord.timestampMs.takeIf { it > 0L }
                ?: firstRecord.signal.timestampMs
            val endedAtMs = (terminal?.timestampMs?.takeIf { it > 0L } ?: terminal?.signal?.timestampMs)
                ?: (answer?.timestampMs?.takeIf { it > 0L } ?: startedAtMs)

            val explicitDuration = group.asReversed()
                .firstNotNullOfOrNull { parseDurationSecondsFromReason(it.signal.reason) }
            val inferredDuration = if (answer != null && terminal != null && endedAtMs > answer.timestampMs) {
                ((endedAtMs - answer.timestampMs) / 1_000L).coerceAtLeast(1L)
            } else {
                null
            }
            val durationSeconds = explicitDuration ?: inferredDuration

            val endedByLocal = terminal != null &&
                localInboxId.isNotBlank() &&
                terminal.senderInboxId.equals(localInboxId, ignoreCase = true)

            val outcome = when {
                durationSeconds != null && durationSeconds > 0L -> ChatCallOutcome.COMPLETED
                answer != null && terminal != null -> ChatCallOutcome.COMPLETED
                terminal != null -> when {
                    terminal.signal.type == CallSignalType.BUSY -> ChatCallOutcome.BUSY
                    terminal.signal.type == CallSignalType.REJECT ||
                        terminal.signal.reason?.startsWith("declined") == true -> ChatCallOutcome.DECLINED
                    else -> ChatCallOutcome.MISSED
                }
                answer != null -> {
                    if (nowMs - answer.timestampMs > STALE_IN_PROGRESS_TIMEOUT_MS) {
                        ChatCallOutcome.COMPLETED
                    } else {
                        ChatCallOutcome.IN_PROGRESS
                    }
                }
                else -> {
                    if (CallSignalingPolicy.isOfferExpired(startedAtMs, nowMs)) {
                        ChatCallOutcome.MISSED
                    } else {
                        ChatCallOutcome.RINGING
                    }
                }
            }

            events.add(
                ChatCallEvent(
                    callId = callId,
                    conversationId = firstRecord.conversationId,
                    callerInboxId = callerInboxId,
                    isOutgoing = isOutgoing,
                    startedAtMs = startedAtMs,
                    endedAtMs = maxOf(startedAtMs, endedAtMs),
                    durationSeconds = durationSeconds,
                    outcome = outcome,
                    endedByLocal = endedByLocal
                )
            )
        }

        return events.sortedBy { it.startedAtMs }
    }

    fun formatCallTitle(event: ChatCallEvent): String {
        return when (event.outcome) {
            ChatCallOutcome.COMPLETED -> if (event.isOutgoing) {
                "Εξερχόμενη κλήση φωνής"
            } else {
                "Εισερχόμενη κλήση φωνής"
            }
            ChatCallOutcome.DECLINED -> "Κλήση φωνής — Απορρίφθηκε"
            ChatCallOutcome.MISSED -> if (event.isOutgoing) {
                "Εξερχόμενη κλήση — Χωρίς απάντηση"
            } else {
                "Αναπάντητη κλήση φωνής"
            }
            ChatCallOutcome.BUSY -> "Κλήση φωνής — Κατειλημμένος"
            ChatCallOutcome.IN_PROGRESS -> "Κλήση φωνής σε εξέλιξη"
            ChatCallOutcome.RINGING -> if (event.isOutgoing) {
                "Εξερχόμενη κλήση..."
            } else {
                "Εισερχόμενη κλήση..."
            }
        }
    }

    fun formatCallSubtitle(event: ChatCallEvent, peerDisplayName: String): String {
        val safePeer = peerDisplayName.ifBlank { "Ο συνομιλητής" }
        val callerLabel = if (event.isOutgoing) {
            "Εσείς καλέσατε"
        } else {
            "$safePeer κάλεσε"
        }

        return when (event.outcome) {
            ChatCallOutcome.COMPLETED -> {
                val dur = event.durationSeconds?.let { formatDurationText(it) }
                if (dur != null) {
                    "$callerLabel • Η κλήση διήρκεσε $dur"
                } else {
                    "$callerLabel • Κλήση ολοκληρώθηκε"
                }
            }
            ChatCallOutcome.DECLINED -> {
                val detail = if (event.endedByLocal) {
                    "Απορρίψατε την κλήση"
                } else {
                    "Ο συνομιλητής απέρριψε την κλήση"
                }
                "$callerLabel • $detail"
            }
            ChatCallOutcome.MISSED -> "$callerLabel • Χωρίς απάντηση"
            ChatCallOutcome.BUSY -> "$callerLabel • Σε άλλη κλήση"
            ChatCallOutcome.IN_PROGRESS -> "$callerLabel • Σε εξέλιξη (DTLS-SRTP)"
            ChatCallOutcome.RINGING -> if (event.isOutgoing) {
                "Εσείς καλείτε τον/την $safePeer..."
            } else {
                "$safePeer καλεί..."
            }
        }
    }

    fun formatDurationText(durationSeconds: Long): String {
        val total = durationSeconds.coerceAtLeast(1L)
        val hours = total / 3_600L
        val mins = (total % 3_600L) / 60L
        val secs = total % 60L

        return when {
            hours > 0L -> String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
            mins > 0L -> {
                val clock = String.format(Locale.US, "%02d:%02d", mins, secs)
                val minWord = if (mins == 1L) "λεπτό" else "λεπτά"
                if (secs == 0L) {
                    "$mins $minWord ($clock)"
                } else {
                    "$mins $minWord $secs δευτ. ($clock)"
                }
            }
            else -> "$secs δευτ."
        }
    }
}

