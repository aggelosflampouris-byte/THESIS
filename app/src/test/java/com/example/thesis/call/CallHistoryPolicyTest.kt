package com.example.thesis.call

import com.example.thesis.xmtp.CallSignal
import com.example.thesis.xmtp.CallSignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryPolicyTest {

    private val localInbox = "inbox_local_user"
    private val peerInbox = "inbox_peer_contact"

    @Test
    fun encodeAndParseHangupDuration_roundTripsCleanly() {
        val encoded = CallHistoryPolicy.encodeHangupReason("hangup", 85L)
        assertEquals("hangup:dur=85", encoded)
        assertEquals(85L, CallHistoryPolicy.parseDurationSecondsFromReason(encoded))
        assertNull(CallHistoryPolicy.parseDurationSecondsFromReason("hangup"))
        assertNull(CallHistoryPolicy.parseDurationSecondsFromReason(null))
    }

    @Test
    fun buildCallEvents_completedCallWithExplicitDuration_formatsCallerAndDuration() {
        val records = listOf(
            DecodedCallSignalRecord(
                messageId = "m1",
                conversationId = "conv1",
                senderInboxId = peerInbox,
                timestampMs = 1_000_000L,
                signal = CallSignal(callId = "c1", type = CallSignalType.OFFER, timestampMs = 1_000_000L, sdp = "v=0")
            ),
            DecodedCallSignalRecord(
                messageId = "m2",
                conversationId = "conv1",
                senderInboxId = localInbox,
                timestampMs = 1_004_000L,
                signal = CallSignal(callId = "c1", type = CallSignalType.ANSWER, timestampMs = 1_004_000L, sdp = "v=0")
            ),
            DecodedCallSignalRecord(
                messageId = "m3",
                conversationId = "conv1",
                senderInboxId = localInbox,
                timestampMs = 1_088_000L,
                signal = CallSignal(
                    callId = "c1",
                    type = CallSignalType.HANGUP,
                    timestampMs = 1_088_000L,
                    reason = CallHistoryPolicy.encodeHangupReason("hangup", 84L)
                )
            )
        )

        val events = CallHistoryPolicy.buildCallEvents(records, localInboxId = localInbox, nowMs = 1_100_000L)
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(ChatCallOutcome.COMPLETED, event.outcome)
        assertFalse(event.isOutgoing)
        assertEquals(84L, event.durationSeconds)
        assertEquals("Εισερχόμενη κλήση φωνής", CallHistoryPolicy.formatCallTitle(event))
        assertEquals(
            "Alice κάλεσε • Η κλήση διήρκεσε 1 λεπτό 24 δευτ. (01:24)",
            CallHistoryPolicy.formatCallSubtitle(event, "Alice")
        )
    }

    @Test
    fun buildCallEvents_declinedAndMissedCalls_formatProperly() {
        val records = listOf(
            DecodedCallSignalRecord(
                messageId = "m1",
                conversationId = "conv1",
                senderInboxId = peerInbox,
                timestampMs = 1_000_000L,
                signal = CallSignal(callId = "c_declined", type = CallSignalType.OFFER, timestampMs = 1_000_000L, sdp = "v=0")
            ),
            DecodedCallSignalRecord(
                messageId = "m2",
                conversationId = "conv1",
                senderInboxId = localInbox,
                timestampMs = 1_003_000L,
                signal = CallSignal(callId = "c_declined", type = CallSignalType.REJECT, timestampMs = 1_003_000L, reason = "declined")
            ),
            DecodedCallSignalRecord(
                messageId = "m3",
                conversationId = "conv1",
                senderInboxId = localInbox,
                timestampMs = 2_000_000L,
                signal = CallSignal(callId = "c_missed", type = CallSignalType.OFFER, timestampMs = 2_000_000L, sdp = "v=0")
            )
        )

        val events = CallHistoryPolicy.buildCallEvents(records, localInboxId = localInbox, nowMs = 2_100_000L)
        assertEquals(2, events.size)

        val declined = events[0]
        assertEquals(ChatCallOutcome.DECLINED, declined.outcome)
        assertTrue(declined.endedByLocal)
        assertEquals("Alice κάλεσε • Απορρίψατε την κλήση", CallHistoryPolicy.formatCallSubtitle(declined, "Alice"))

        val missed = events[1]
        assertEquals(ChatCallOutcome.MISSED, missed.outcome)
        assertTrue(missed.isOutgoing)
        assertEquals("Εσείς καλέσατε • Χωρίς απάντηση", CallHistoryPolicy.formatCallSubtitle(missed, "Alice"))
    }
}

