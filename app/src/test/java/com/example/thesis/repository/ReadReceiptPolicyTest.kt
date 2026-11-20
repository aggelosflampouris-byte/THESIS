package com.example.thesis.repository

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmtp.android.library.codecs.ContentTypeReadReceipt
import org.xmtp.android.library.codecs.ContentTypeText
import org.xmtp.android.library.codecs.ReadReceipt
import org.xmtp.android.library.libxmtp.DecodedMessage
import java.util.Date

class ReadReceiptPolicyTest {

    private val localInbox = "local_inbox_001"
    private val peerInbox = "peer_inbox_002"

    private fun mockTextMessage(
        id: String,
        sender: String,
        timestampMs: Long,
        status: DecodedMessage.MessageDeliveryStatus = DecodedMessage.MessageDeliveryStatus.PUBLISHED
    ): DecodedMessage {
        val msg = mockk<DecodedMessage>(relaxed = true)
        every { msg.id } returns id
        every { msg.senderInboxId } returns sender
        every { msg.sentAt } returns Date(timestampMs)
        every { msg.sentAtNs } returns timestampMs * 1_000_000L
        every { msg.deliveryStatus } returns status
        every { msg.encodedContent.type } returns ContentTypeText
        every { msg.content<Any>() } returns "Message $id"
        every { msg.body } returns "Message $id"
        return msg
    }

    private fun mockReceiptMessage(id: String, sender: String, timestampMs: Long): DecodedMessage {
        val msg = mockk<DecodedMessage>(relaxed = true)
        every { msg.id } returns id
        every { msg.senderInboxId } returns sender
        every { msg.sentAt } returns Date(timestampMs)
        every { msg.sentAtNs } returns timestampMs * 1_000_000L
        every { msg.encodedContent.type } returns ContentTypeReadReceipt
        every { msg.content<Any>() } returns ReadReceipt
        every { msg.body } returns ""
        return msg
    }

    @Test
    fun test_computePeerLastReadTimestampMs_usesLatestPeerReceiptOrReplyIgnoringOwn() {
        val ownMsg = mockTextMessage("own_1", localInbox, 5_000L)
        val ownReceipt = mockReceiptMessage("own_rcpt", localInbox, 9_000L)
        val peerText = mockTextMessage("peer_1", peerInbox, 3_000L)
        val peerReceipt = mockReceiptMessage("peer_rcpt", peerInbox, 7_000L)

        assertEquals(
            7_000L,
            ReadReceiptPolicy.computePeerLastReadTimestampMs(
                listOf(ownMsg, ownReceipt, peerText, peerReceipt),
                localInbox
            )
        )
        assertNull(
            ReadReceiptPolicy.computePeerLastReadTimestampMs(listOf(ownMsg, ownReceipt), localInbox)
        )
    }

    @Test
    fun test_isMessageSeenByPeer_checksSenderPublishedStatusAndTimestampWatermark() {
        val earlierPublished = mockTextMessage("m1", localInbox, 4_000L)
        val exactWatermark = mockTextMessage("m2", localInbox, 7_000L)
        val laterPublished = mockTextMessage("m3", localInbox, 8_000L)
        val unpublishedMsg = mockTextMessage(
            "m4",
            localInbox,
            3_000L,
            status = DecodedMessage.MessageDeliveryStatus.UNPUBLISHED
        )
        val peerMsg = mockTextMessage("p1", peerInbox, 3_000L)

        assertTrue(ReadReceiptPolicy.isMessageSeenByPeer(earlierPublished, localInbox, 7_000L))
        assertTrue(ReadReceiptPolicy.isMessageSeenByPeer(exactWatermark, localInbox, 7_000L))
        assertFalse(ReadReceiptPolicy.isMessageSeenByPeer(laterPublished, localInbox, 7_000L))
        assertFalse(ReadReceiptPolicy.isMessageSeenByPeer(unpublishedMsg, localInbox, 7_000L))
        assertFalse(ReadReceiptPolicy.isMessageSeenByPeer(peerMsg, localInbox, 7_000L))
        assertFalse(ReadReceiptPolicy.isMessageSeenByPeer(earlierPublished, localInbox, null))
    }

    @Test
    fun test_shouldSendReadReceipt_enforcesForegroundNoLogsAndDebouncesAlreadyRead() {
        val peerMsg = mockTextMessage("peer_msg_1", peerInbox, 5_000L)

        assertTrue(
            ReadReceiptPolicy.shouldSendReadReceipt(
                latestPeerMessage = peerMsg,
                localLastReadTimestampMs = 4_000L,
                lastSentForPeerMsgId = null,
                isAppInForeground = true,
                isLogsScreenOpen = false
            )
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipt(
                latestPeerMessage = peerMsg,
                localLastReadTimestampMs = null,
                lastSentForPeerMsgId = null,
                isAppInForeground = false,
                isLogsScreenOpen = false
            )
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipt(
                latestPeerMessage = peerMsg,
                localLastReadTimestampMs = null,
                lastSentForPeerMsgId = null,
                isAppInForeground = true,
                isLogsScreenOpen = true
            )
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipt(
                latestPeerMessage = peerMsg,
                localLastReadTimestampMs = null,
                lastSentForPeerMsgId = "peer_msg_1",
                isAppInForeground = true,
                isLogsScreenOpen = false
            )
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipt(
                latestPeerMessage = peerMsg,
                localLastReadTimestampMs = 5_000L,
                lastSentForPeerMsgId = null,
                isAppInForeground = true,
                isLogsScreenOpen = false
            )
        )
        assertFalse(
            ReadReceiptPolicy.shouldSendReadReceipt(
                latestPeerMessage = null,
                localLastReadTimestampMs = null,
                lastSentForPeerMsgId = null,
                isAppInForeground = true,
                isLogsScreenOpen = false
            )
        )
    }

    @Test
    fun test_computeLocalLastReadTimestampMs_ignoresPeerReceipts() {
        val peerReceipt = mockReceiptMessage("peer_rcpt", peerInbox, 9_500L)
        val localReceipt = mockReceiptMessage("local_rcpt", localInbox, 6_000L)
        val localText = mockTextMessage("local_txt", localInbox, 12_000L)

        assertEquals(
            6_000L,
            ReadReceiptPolicy.computeLocalLastReadTimestampMs(
                listOf(peerReceipt, localReceipt, localText),
                localInbox
            )
        )
    }
}
