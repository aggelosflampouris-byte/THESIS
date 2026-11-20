package com.example.thesis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NicknamePolicyTest {

    @Test
    fun test_sanitize_stripsControlCharsCollapsesSpacesAndEnforcesMaxLength() {
        assertEquals("Μαρία Παπαδοπούλου", NicknamePolicy.sanitize("  Μαρία\n\t Παπαδοπούλου  "))
        assertNull(NicknamePolicy.sanitize("   \n\t  "))
        assertNull(NicknamePolicy.sanitize(null))

        val tooLong = "A".repeat(45)
        val trimmed = NicknamePolicy.sanitize(tooLong)
        assertEquals(NicknamePolicy.MAX_NICKNAME_LENGTH, trimmed?.length)
    }

    @Test
    fun test_codec_roundtripsNormalizedKeysAndFiltersCorruptEntries() {
        val original = mapOf(
            "0xABCDEF1234" to "Alice",
            "peer_inbox_id_2" to "  Bob\nTwo  ",
            "" to "Ignored",
            "blank_nick" to "   "
        )
        val serialized = NicknameStoreCodec.serializeMap(original)
        val deserialized = NicknameStoreCodec.deserializeMap(serialized + "\nmalformed_line_without_tab")

        assertEquals(
            mapOf(
                "0xabcdef1234" to "Alice",
                "peer_inbox_id_2" to "Bob Two"
            ),
            deserialized
        )
        assertTrue(NicknameStoreCodec.deserializeMap(null).isEmpty())
    }

    @Test
    fun test_inMemoryStorage_updatesAndClearsOnGdprWipe() {
        val store = InMemoryNicknameStorage()
        assertEquals("Aggelos", store.setMyNickname("  Aggelos "))
        assertEquals("Maria", store.setLocalContactNickname("0xPEER1", "Maria"))
        assertEquals("Maria MLS", store.setSharedPeerNickname("0xpeer1", "Maria MLS"))

        val snap = store.snapshot.value
        assertEquals("Aggelos", snap.myNickname)
        assertEquals("Maria", snap.localNicknames["0xpeer1"])
        assertEquals("Maria MLS", snap.sharedNicknames["0xpeer1"])

        store.clear()
        assertEquals(NicknameSnapshot(), store.snapshot.value)
    }

    @Test
    fun test_resolutionPrecedence_prefersLocalOverrideThenSharedThenGroupOrMasked() {
        val snap = NicknameSnapshot(
            myNickname = "Ulysses",
            localNicknames = mapOf("peer_a" to "My Friend A"),
            sharedNicknames = mapOf("peer_a" to "Peer A Shared", "peer_b" to "Peer B Shared")
        )

        assertEquals(
            "My Friend A",
            NicknamePolicy.resolveConversationTitle("PEER_A", isDm = true, isLoopback = false, groupName = null, fallbackMaskedPeer = "peer...a", snapshot = snap)
        )
        assertEquals(
            "Peer B Shared",
            NicknamePolicy.resolveConversationTitle("peer_b", isDm = true, isLoopback = false, groupName = null, fallbackMaskedPeer = "peer...b", snapshot = snap)
        )
        assertEquals(
            "DM: peer...c",
            NicknamePolicy.resolveConversationTitle("peer_c", isDm = true, isLoopback = false, groupName = null, fallbackMaskedPeer = "peer...c", snapshot = snap)
        )
        assertEquals(
            "My Friend A",
            NicknamePolicy.resolveNotificationSenderTitle("peer_a", "conv_1", null, snap)
        )
        assertEquals(
            "Peer B Shared",
            NicknamePolicy.resolveNotificationSenderTitle("peer_b", "conv_2", null, snap)
        )
        assertNull(
            NicknamePolicy.resolveNotificationSenderTitle("peer_unknown", "conv_3", null, snap)
        )
    }
}
