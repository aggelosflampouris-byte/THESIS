package com.example.thesis.repository

import org.xmtp.android.library.libxmtp.DecodedMessage

internal object ReadReceiptPolicy {

    fun computePeerLastReadTimestampMs(
        rawMessages: List<DecodedMessage>,
        localInboxId: String
    ): Long? {
        if (localInboxId.isBlank()) return null
        var maxTs: Long? = null
        for (msg in rawMessages) {
            val sender = msg.senderInboxId
            if (sender.isBlank() || sender.equals(localInboxId, ignoreCase = true)) continue
            // Cualquier acuse XIP-25 o respuesta posterior del par confirma lectura hasta ese instante.
            if (!msg.isReadReceiptMessage() && !msg.isDisplayableUserMessage()) continue
            val ts = msg.resolvedTimestampMs()
            if (ts > 0L && (maxTs == null || ts > maxTs)) {
                maxTs = ts
            }
        }
        return maxTs
    }

    fun computeLocalLastReadTimestampMs(
        rawMessages: List<DecodedMessage>,
        localInboxId: String
    ): Long? {
        if (localInboxId.isBlank()) return null
        var maxTs: Long? = null
        for (msg in rawMessages) {
            if (!msg.senderInboxId.equals(localInboxId, ignoreCase = true)) continue
            if (!msg.isReadReceiptMessage()) continue
            val ts = msg.resolvedTimestampMs()
            if (ts > 0L && (maxTs == null || ts > maxTs)) {
                maxTs = ts
            }
        }
        return maxTs
    }

    fun isMessageSeenByPeer(
        message: DecodedMessage,
        localInboxId: String,
        peerLastReadTimestampMs: Long?
    ): Boolean {
        if (peerLastReadTimestampMs == null || peerLastReadTimestampMs <= 0L) return false
        if (localInboxId.isBlank() || !message.senderInboxId.equals(localInboxId, ignoreCase = true)) {
            return false
        }
        if (message.deliveryStatus != DecodedMessage.MessageDeliveryStatus.PUBLISHED) {
            return false
        }
        val ts = message.resolvedTimestampMs()
        return ts in 1L..peerLastReadTimestampMs
    }

    fun shouldSendReadReceipt(
        latestPeerMessage: DecodedMessage?,
        localLastReadTimestampMs: Long?,
        lastSentForPeerMsgId: String?,
        isAppInForeground: Boolean,
        isLogsScreenOpen: Boolean
    ): Boolean {
        if (!isAppInForeground || isLogsScreenOpen) return false
        if (latestPeerMessage == null || latestPeerMessage.id.isBlank()) return false
        if (latestPeerMessage.id == lastSentForPeerMsgId) return false
        val peerTs = latestPeerMessage.resolvedTimestampMs()
        return localLastReadTimestampMs == null || peerTs > localLastReadTimestampMs
    }
}
