package com.example.thesis.repository

import com.example.thesis.identity.NicknameSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmtp.android.library.libxmtp.PermissionLevel

class GroupManagementPolicyTest {

    private val aliceInbox = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bobInbox = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val selfInbox = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    private val selfEth = "0x1111111111111111111111111111111111111111"
    private val peerEth = "0x2222222222222222222222222222222222222222"

    @Test
    fun sanitizeGroupName_stripsControlsCollapsesSpacesAndCapsLength() {
        assertNull(GroupManagementPolicy.sanitizeGroupName(null))
        assertNull(GroupManagementPolicy.sanitizeGroupName("   \n\t  "))
        assertEquals("Design Team", GroupManagementPolicy.sanitizeGroupName("  Design\t\nTeam  "))

        val oversized = "A".repeat(GroupManagementPolicy.MAX_GROUP_NAME_LENGTH + 15)
        val sanitized = GroupManagementPolicy.sanitizeGroupName(oversized)
        assertEquals(GroupManagementPolicy.MAX_GROUP_NAME_LENGTH, sanitized?.length)
    }

    @Test
    fun sanitizeGroupDescription_cleansAndTruncates() {
        assertEquals("", GroupManagementPolicy.sanitizeGroupDescription(null))
        assertEquals("", GroupManagementPolicy.sanitizeGroupDescription("\r\n   "))
        assertEquals("Thesis Sync", GroupManagementPolicy.sanitizeGroupDescription("Thesis\n\tSync"))

        val oversized = "B".repeat(GroupManagementPolicy.MAX_GROUP_DESCRIPTION_LENGTH + 20)
        assertEquals(
            GroupManagementPolicy.MAX_GROUP_DESCRIPTION_LENGTH,
            GroupManagementPolicy.sanitizeGroupDescription(oversized).length
        )
    }

    @Test
    fun parsePeerIdentifier_recognizesInboxIdAndEthAddress() {
        val parsedInbox = GroupManagementPolicy.parsePeerIdentifier("@0x${aliceInbox.uppercase()}")
        assertEquals(ParsedPeerIdentifier.InboxId(aliceInbox), parsedInbox)

        val parsedEth = GroupManagementPolicy.parsePeerIdentifier(" ${peerEth.uppercase()} ")
        assertEquals(ParsedPeerIdentifier.EthereumAddress(peerEth), parsedEth)

        assertNull(GroupManagementPolicy.parsePeerIdentifier("invalid-peer"))
    }

    @Test
    fun deduplicatePeerIdentifiers_excludesSelfAndDeduplicatesCaseInsensitively() {
        val deduped = GroupManagementPolicy.deduplicatePeerIdentifiers(
            rawList = listOf(
                selfInbox.uppercase(),
                selfEth.uppercase(),
                aliceInbox,
                "0x${aliceInbox.uppercase()}",
                "bad-id",
                peerEth.uppercase(),
                peerEth
            ),
            localInboxId = selfInbox,
            localEthAddress = selfEth
        )

        assertEquals(
            listOf(
                ParsedPeerIdentifier.InboxId(aliceInbox),
                ParsedPeerIdentifier.EthereumAddress(peerEth)
            ),
            deduped
        )
    }

    @Test
    fun resolveSenderDisplayLabel_prefersLocalAndSharedNicknamesThenMasksInboxId() {
        val snapshot = NicknameSnapshot(
            localNicknames = mapOf(aliceInbox to "Αλίκη (Τοπικό)"),
            sharedNicknames = mapOf(
                aliceInbox to "Alice",
                bobInbox to "Μπομπ"
            )
        )

        assertEquals("Αλίκη (Τοπικό)", GroupManagementPolicy.resolveSenderDisplayLabel(aliceInbox, snapshot))
        assertEquals("Μπομπ", GroupManagementPolicy.resolveSenderDisplayLabel(bobInbox, snapshot))
        assertEquals(
            "cccccc…cccc",
            GroupManagementPolicy.resolveSenderDisplayLabel("cccccccccccccccccccccccc", snapshot)
        )
        assertEquals("shortId", GroupManagementPolicy.resolveSenderDisplayLabel("shortId", snapshot))
    }

    @Test
    fun formatGroupMemberCountSubtitle_handlesSingularAndPlural() {
        assertEquals("1 μέλος", GroupManagementPolicy.formatGroupMemberCountSubtitle(1))
        assertEquals("3 μέλη", GroupManagementPolicy.formatGroupMemberCountSubtitle(3))
    }

    @Test
    fun permissionChecks_enforceActiveAndRoleHierarchy() {
        val selfSuperAdmin = GroupMemberInfo(selfInbox, selfEth, PermissionLevel.SUPER_ADMIN, isLocalUser = true)
        val otherAdmin = GroupMemberInfo(aliceInbox, null, PermissionLevel.ADMIN, isLocalUser = false)
        val regularMember = GroupMemberInfo(bobInbox, peerEth, PermissionLevel.MEMBER, isLocalUser = false)
        val otherSuperAdmin = GroupMemberInfo("dddddddd", null, PermissionLevel.SUPER_ADMIN, isLocalUser = false)

        val superAdminGroup = GroupDetailsSnapshot(
            conversationId = "g1",
            name = "Core",
            description = "",
            members = listOf(selfSuperAdmin, otherAdmin, regularMember, otherSuperAdmin),
            isLocalAdmin = true,
            isLocalSuperAdmin = true,
            isActive = true
        )
        assertTrue(GroupManagementPolicy.canManageMembers(superAdminGroup))
        assertFalse(GroupManagementPolicy.canRemoveMember(superAdminGroup, selfSuperAdmin))
        assertFalse(GroupManagementPolicy.canRemoveMember(superAdminGroup, otherSuperAdmin))
        assertTrue(GroupManagementPolicy.canRemoveMember(superAdminGroup, otherAdmin))
        assertTrue(GroupManagementPolicy.canRemoveMember(superAdminGroup, regularMember))

        val adminGroup = superAdminGroup.copy(isLocalSuperAdmin = false, isLocalAdmin = true)
        assertTrue(GroupManagementPolicy.canManageMembers(adminGroup))
        assertFalse(GroupManagementPolicy.canRemoveMember(adminGroup, otherAdmin))
        assertTrue(GroupManagementPolicy.canRemoveMember(adminGroup, regularMember))

        val memberOnlyGroup = superAdminGroup.copy(isLocalSuperAdmin = false, isLocalAdmin = false)
        assertFalse(GroupManagementPolicy.canManageMembers(memberOnlyGroup))
        assertFalse(GroupManagementPolicy.canRemoveMember(memberOnlyGroup, regularMember))

        val inactiveGroup = superAdminGroup.copy(isActive = false)
        assertFalse(GroupManagementPolicy.canManageMembers(inactiveGroup))
        assertFalse(GroupManagementPolicy.canRemoveMember(inactiveGroup, regularMember))
    }
}

