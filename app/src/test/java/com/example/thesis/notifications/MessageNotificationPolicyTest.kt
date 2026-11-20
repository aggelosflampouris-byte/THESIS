package com.example.thesis.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNotificationPolicyTest {

    @Test
    fun test_shouldNotify_allowsRemoteMessageInBackgroundOrDashboardOrOtherChat() {
        assertTrue(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_1",
                conversationId = "conv_a",
                senderInboxId = "peer_inbox",
                localInboxId = "local_inbox",
                body = "Καλημέρα",
                isAppInForeground = false,
                activeConversationId = "conv_a",
                isLogsScreenOpen = false,
                alreadyNotified = false
            )
        )
        assertTrue(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_2",
                conversationId = "conv_a",
                senderInboxId = "peer_inbox",
                localInboxId = "local_inbox",
                body = "Καλημέρα",
                isAppInForeground = true,
                activeConversationId = null,
                isLogsScreenOpen = false,
                alreadyNotified = false
            )
        )
        assertTrue(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_3",
                conversationId = "conv_a",
                senderInboxId = "peer_inbox",
                localInboxId = "local_inbox",
                body = "Καλημέρα",
                isAppInForeground = true,
                activeConversationId = "conv_b",
                isLogsScreenOpen = false,
                alreadyNotified = false
            )
        )
    }

    @Test
    fun test_shouldNotify_suppressesOwnMessagesDuplicatesAndBlankPayloads() {
        assertFalse(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_own",
                conversationId = "conv_a",
                senderInboxId = "LOCAL_INBOX",
                localInboxId = "local_inbox",
                body = "Loopback or self message",
                isAppInForeground = false,
                activeConversationId = null,
                isLogsScreenOpen = false,
                alreadyNotified = false
            )
        )
        assertFalse(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_dup",
                conversationId = "conv_a",
                senderInboxId = "peer_inbox",
                localInboxId = "local_inbox",
                body = "Repeated frame",
                isAppInForeground = false,
                activeConversationId = null,
                isLogsScreenOpen = false,
                alreadyNotified = true
            )
        )
        assertFalse(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_blank",
                conversationId = "conv_a",
                senderInboxId = "peer_inbox",
                localInboxId = "local_inbox",
                body = "   ",
                isAppInForeground = false,
                activeConversationId = null,
                isLogsScreenOpen = false,
                alreadyNotified = false
            )
        )
    }

    @Test
    fun test_shouldNotify_suppressesWhenActivelyViewingSameConversationInForeground() {
        assertFalse(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_active",
                conversationId = "conv_a",
                senderInboxId = "peer_inbox",
                localInboxId = "local_inbox",
                body = "Active chat ping",
                isAppInForeground = true,
                activeConversationId = "conv_a",
                isLogsScreenOpen = false,
                alreadyNotified = false
            )
        )
    }

    @Test
    fun test_shouldNotify_allowsWhenSameConversationSelectedButLogsScreenOpen() {
        assertTrue(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_logs",
                conversationId = "conv_a",
                senderInboxId = "peer_inbox",
                localInboxId = "local_inbox",
                body = "Incoming while inspecting telemetry",
                isAppInForeground = true,
                activeConversationId = "conv_a",
                isLogsScreenOpen = true,
                alreadyNotified = false
            )
        )
    }

    @Test
    fun test_formatSenderTitleAndBodyPreview_masksSenderAndTruncatesLongMessages() {
        assertEquals(
            "Group Alpha",
            MessageNotificationPolicy.formatSenderTitle("Group Alpha", "dad8bd17b4bb41043114d47875cddfeb99f80459930105c916a1f3c9f5f0fca5")
        )
        assertEquals(
            "MLS DM (dad8bd...fca5)",
            MessageNotificationPolicy.formatSenderTitle(null, "dad8bd17b4bb41043114d47875cddfeb99f80459930105c916a1f3c9f5f0fca5")
        )

        val longText = "  Line 1\n\n  " + "A".repeat(300)
        val preview = MessageNotificationPolicy.formatBodyPreview(longText)
        assertEquals(MessageNotificationPolicy.MAX_PREVIEW_LENGTH, preview.length)
        assertTrue(preview.endsWith("…"))
        assertFalse(preview.contains("\n"))
    }

    @Test
    fun test_notificationIdFor_isDeterministicPositiveAndDistinctAcrossConversations() {
        val idA1 = MessageNotificationPolicy.notificationIdFor("conv_alpha")
        val idA2 = MessageNotificationPolicy.notificationIdFor("conv_alpha")
        val idB = MessageNotificationPolicy.notificationIdFor("conv_beta")

        assertEquals(idA1, idA2)
        assertTrue(idA1 >= 1_000)
        assertNotEquals(idA1, idB)
    }

    @Test
    fun test_shouldNotify_allowsOutsideAppEvenWhenConversationWasPreviouslySelected() {
        assertTrue(
            MessageNotificationPolicy.shouldNotify(
                messageId = "msg_bg_selected",
                conversationId = "conv_a",
                senderInboxId = "peer_inbox",
                localInboxId = "local_inbox",
                body = "Arrived while user was outside the app",
                isAppInForeground = false,
                activeConversationId = "conv_a",
                isLogsScreenOpen = false,
                alreadyNotified = false
            )
        )
    }
}
