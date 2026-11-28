package com.example.thesis.repository

import com.example.thesis.identity.NicknamePolicy
import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.util.CryptoUtils
import org.xmtp.android.library.libxmtp.PermissionLevel
import java.util.Locale

sealed interface ParsedPeerIdentifier {
    data class InboxId(val inboxId: String) : ParsedPeerIdentifier
    data class EthereumAddress(val address: String) : ParsedPeerIdentifier
}

data class GroupMemberInfo(
    val inboxId: String,
    val ethAddress: String?,
    val permissionLevel: PermissionLevel,
    val isLocalUser: Boolean
)

data class GroupDetailsSnapshot(
    val conversationId: String,
    val name: String,
    val description: String,
    val members: List<GroupMemberInfo>,
    val isLocalAdmin: Boolean,
    val isLocalSuperAdmin: Boolean,
    val isActive: Boolean
)

/**
 * Políticas puras de validación, normalización y permisos para grupos MLS (RFC 9420).
 */
object GroupManagementPolicy {

    const val MAX_GROUP_NAME_LENGTH = 60
    const val MAX_GROUP_DESCRIPTION_LENGTH = 200
    const val LOOPBACK_GROUP_NAME = "MLS Research Loopback"

    fun isLoopbackGroupName(name: String?): Boolean = name == LOOPBACK_GROUP_NAME

    fun sanitizeGroupName(raw: String?): String? {
        if (raw == null) return null
        // Sustituimos caracteres de control ISO por espacios para evitar saltos o texto oculto en encabezados.
        val cleaned = collapseWhitespaceWithoutControls(raw)
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= MAX_GROUP_NAME_LENGTH) {
            cleaned
        } else {
            cleaned.take(MAX_GROUP_NAME_LENGTH).trimEnd()
        }
    }

    fun sanitizeGroupDescription(raw: String?): String {
        if (raw == null) return ""
        val cleaned = collapseWhitespaceWithoutControls(raw)
        if (cleaned.isEmpty()) return ""
        return if (cleaned.length <= MAX_GROUP_DESCRIPTION_LENGTH) {
            cleaned
        } else {
            cleaned.take(MAX_GROUP_DESCRIPTION_LENGTH).trimEnd()
        }
    }

    fun parsePeerIdentifier(raw: String): ParsedPeerIdentifier? {
        CryptoUtils.sanitiseInboxId(raw)?.let { return ParsedPeerIdentifier.InboxId(it) }
        CryptoUtils.sanitiseEthereumAddress(raw)?.let { return ParsedPeerIdentifier.EthereumAddress(it) }
        return null
    }

    fun deduplicatePeerIdentifiers(
        rawList: List<String>,
        localInboxId: String? = null,
        localEthAddress: String? = null
    ): List<ParsedPeerIdentifier> {
        val selfInbox = localInboxId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val selfEth = localEthAddress?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val seenKeys = LinkedHashSet<String>()
        val result = ArrayList<ParsedPeerIdentifier>()

        for (raw in rawList) {
            val parsed = parsePeerIdentifier(raw) ?: continue
            val key = when (parsed) {
                is ParsedPeerIdentifier.InboxId -> {
                    if (selfInbox != null && parsed.inboxId == selfInbox) continue
                    "inbox:${parsed.inboxId}"
                }
                is ParsedPeerIdentifier.EthereumAddress -> {
                    if (selfEth != null && parsed.address == selfEth) continue
                    "eth:${parsed.address}"
                }
            }
            if (seenKeys.add(key)) {
                result.add(parsed)
            }
        }
        return result
    }

    fun resolveSenderDisplayLabel(senderInboxId: String, snapshot: NicknameSnapshot): String {
        val nick = NicknamePolicy.resolveContactNickname(senderInboxId, snapshot)
        if (!nick.isNullOrBlank()) return nick
        return if (senderInboxId.length >= 14) {
            "${senderInboxId.take(6)}…${senderInboxId.takeLast(4)}"
        } else {
            senderInboxId
        }
    }

    fun formatGroupMemberCountSubtitle(memberCount: Int): String =
        if (memberCount == 1) "1 μέλος" else "$memberCount μέλη"

    fun canManageMembers(snapshot: GroupDetailsSnapshot): Boolean =
        snapshot.isActive && (snapshot.isLocalAdmin || snapshot.isLocalSuperAdmin)

    fun canRemoveMember(snapshot: GroupDetailsSnapshot, target: GroupMemberInfo): Boolean {
        if (!snapshot.isActive || target.isLocalUser) return false
        return when {
            snapshot.isLocalSuperAdmin -> target.permissionLevel != PermissionLevel.SUPER_ADMIN
            snapshot.isLocalAdmin -> target.permissionLevel == PermissionLevel.MEMBER
            else -> false
        }
    }

    private fun collapseWhitespaceWithoutControls(raw: String): String {
        return buildString(raw.length) {
            for (ch in raw) {
                append(if (ch.isISOControl()) ' ' else ch)
            }
        }.trim().replace(Regex("\\s+"), " ")
    }
}

