package com.example.thesis.notifications

internal object MessageNotificationPolicy {
    const val MAX_PREVIEW_LENGTH = 240

    fun shouldNotify(
        messageId: String,
        conversationId: String,
        senderInboxId: String,
        localInboxId: String?,
        body: String,
        isAppInForeground: Boolean,
        activeConversationId: String?,
        isLogsScreenOpen: Boolean,
        alreadyNotified: Boolean
    ): Boolean {
        if (messageId.isBlank() || conversationId.isBlank() || body.isBlank() || alreadyNotified) {
            return false
        }
        if (localInboxId != null && senderInboxId.equals(localInboxId, ignoreCase = true)) {
            return false
        }
        // Evitamos interrumpir al usuario si ya esta leyendo activamente esa misma conversacion en primer plano.
        if (isAppInForeground && !isLogsScreenOpen && activeConversationId == conversationId) {
            return false
        }
        return true
    }

    fun formatSenderTitle(conversationTitle: String?, senderInboxId: String): String {
        val cleaned = conversationTitle?.trim().orEmpty()
        return if (cleaned.isNotEmpty()) {
            cleaned
        } else {
            "MLS DM (${maskIdentifier(senderInboxId)})"
        }
    }

    fun formatBodyPreview(rawBody: String): String {
        val collapsed = rawBody.trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length <= MAX_PREVIEW_LENGTH) {
            collapsed
        } else {
            collapsed.take(MAX_PREVIEW_LENGTH - 1) + "…"
        }
    }

    fun notificationIdFor(conversationId: String): Int {
        val positiveHash = conversationId.hashCode() and 0x7FFFFFFF
        return (positiveHash % 1_000_000) + 1_000
    }

    private fun maskIdentifier(id: String): String {
        return if (id.length >= 14) {
            "${id.take(6)}...${id.takeLast(4)}"
        } else {
            id
        }
    }
}
