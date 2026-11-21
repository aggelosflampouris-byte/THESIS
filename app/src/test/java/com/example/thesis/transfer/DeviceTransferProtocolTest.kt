package com.example.thesis.transfer

import com.example.thesis.identity.NicknameSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.AEADBadTagException

class DeviceTransferProtocolTest {

    @Test
    fun test_buildAndParsePairingUri_roundtripsPrivateHostsPortKeyAndSas() {
        val sessionKey = ByteArray(DeviceTransferProtocol.SESSION_KEY_BYTES) { (it + 7).toByte() }
        val built = DeviceTransferProtocol.buildPairingUri(
            hosts = listOf("192.168.43.1", "10.0.0.5"),
            port = 45678,
            sessionKey = sessionKey
        )

        val parsed = DeviceTransferProtocol.parsePairingUri(built.uri)
        assertNotNull(parsed)
        assertEquals(listOf("192.168.43.1", "10.0.0.5"), parsed!!.hosts)
        assertEquals(45678, parsed.port)
        assertArrayEquals(sessionKey, parsed.sessionKey)
        assertEquals(DeviceTransferProtocol.computeSasCode(sessionKey), parsed.sasCode)
        assertTrue(parsed.sasCode.matches(Regex("^\\d{3} \\d{3}$")))

        parsed.wipe()
        assertTrue(parsed.sessionKey.all { it == 0.toByte() })
    }

    @Test
    fun test_parsePairingUri_rejectsNonPrivateHostsPublicIpsBadPortsAndHostnames() {
        val validKeyB64 = DeviceTransferProtocol.buildPairingUri(
            listOf("192.168.1.10"),
            51234,
            ByteArray(32) { 1 }
        ).uri.substringAfter("&k=")

        assertNull(DeviceTransferProtocol.parsePairingUri("http://v1?h=192.168.1.10&p=51234&k=$validKeyB64"))
        assertNull(DeviceTransferProtocol.parsePairingUri("thesis-xfer://v2?h=192.168.1.10&p=51234&k=$validKeyB64"))
        assertNull(DeviceTransferProtocol.parsePairingUri("thesis-xfer://v1?h=evil.example.com&p=51234&k=$validKeyB64"))
        assertNull(DeviceTransferProtocol.parsePairingUri("thesis-xfer://v1?h=8.8.8.8&p=51234&k=$validKeyB64"))
        assertNull(DeviceTransferProtocol.parsePairingUri("thesis-xfer://v1?h=169.254.1.1&p=51234&k=$validKeyB64"))
        assertNull(DeviceTransferProtocol.parsePairingUri("thesis-xfer://v1?h=127.0.0.1&p=51234&k=$validKeyB64", allowLoopback = false))
        assertNotNull(DeviceTransferProtocol.parsePairingUri("thesis-xfer://v1?h=127.0.0.1&p=51234&k=$validKeyB64", allowLoopback = true))
        assertNull(DeviceTransferProtocol.parsePairingUri("thesis-xfer://v1?h=192.168.1.10&p=80&k=$validKeyB64"))
        assertNull(DeviceTransferProtocol.parsePairingUri("thesis-xfer://v1?h=192.168.1.10&p=51234&k=c2hvcnQ"))
    }

    @Test
    fun test_derivedSubkeysAreDirectionalAndDistinct() {
        val master = ByteArray(32) { (it * 3 + 1).toByte() }
        val s2r = DeviceTransferCrypto.deriveSubkey(master, DeviceTransferProtocol.LABEL_S2R)
        val r2s = DeviceTransferCrypto.deriveSubkey(master, DeviceTransferProtocol.LABEL_R2S)
        val archive = DeviceTransferCrypto.deriveSubkey(master, DeviceTransferProtocol.LABEL_ARCHIVE)

        assertEquals(32, s2r.size)
        assertFalse(s2r.contentEquals(r2s))
        assertFalse(s2r.contentEquals(archive))
        assertFalse(r2s.contentEquals(archive))
    }

    @Test
    fun test_encryptedFrames_roundtripAndRejectTamperReplayAndWrongKey() {
        val key = ByteArray(32) { 0x42.toByte() }
        val wrongKey = ByteArray(32) { 0x43.toByte() }
        val payload = "sensitive-secp256k1-metadata".toByteArray(Charsets.UTF_8)

        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            DeviceTransferCrypto.writeEncryptedFrame(
                out = out,
                key = key,
                frameType = DeviceTransferProtocol.FRAME_METADATA_AND_KEYS,
                seq = 0L,
                plaintext = payload
            )
        }
        val wire = baos.toByteArray()

        val decoded = DeviceTransferCrypto.readEncryptedFrame(
            input = DataInputStream(ByteArrayInputStream(wire)),
            key = key,
            expectedFrameType = DeviceTransferProtocol.FRAME_METADATA_AND_KEYS,
            expectedSeq = 0L,
            maxPlaintextBytes = 1024
        )
        assertArrayEquals(payload, decoded.payload)

        // Alteramos el ultimo byte del ciphertext/tag para comprobar deteccion AEAD.
        val tampered = wire.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte() }
        val tamperFailed = runCatching {
            DeviceTransferCrypto.readEncryptedFrame(
                input = DataInputStream(ByteArrayInputStream(tampered)),
                key = key,
                expectedFrameType = DeviceTransferProtocol.FRAME_METADATA_AND_KEYS,
                expectedSeq = 0L,
                maxPlaintextBytes = 1024
            )
        }.exceptionOrNull()
        assertTrue(tamperFailed is AEADBadTagException)

        val wrongKeyFailed = runCatching {
            DeviceTransferCrypto.readEncryptedFrame(
                input = DataInputStream(ByteArrayInputStream(wire)),
                key = wrongKey,
                expectedFrameType = DeviceTransferProtocol.FRAME_METADATA_AND_KEYS,
                expectedSeq = 0L,
                maxPlaintextBytes = 1024
            )
        }.exceptionOrNull()
        assertTrue(wrongKeyFailed is AEADBadTagException)

        val replaySeqFailed = runCatching {
            DeviceTransferCrypto.readEncryptedFrame(
                input = DataInputStream(ByteArrayInputStream(wire)),
                key = key,
                expectedFrameType = DeviceTransferProtocol.FRAME_METADATA_AND_KEYS,
                expectedSeq = 1L,
                maxPlaintextBytes = 1024
            )
        }.exceptionOrNull()
        assertTrue(replaySeqFailed is SecurityException)
    }

    @Test
    fun test_binaryEnvelopeCodecs_roundtripMetadataAndAck() {
        val original = TransferMetadataEnvelope(
            challengeNonce = ByteArray(16) { 0x11 },
            walletAddress = "0x71c7656ec7ab88b098defb751b7401b5f6d8976f",
            identityPrivateKey = ByteArray(32) { 0x22 },
            nicknames = NicknameSnapshot(
                myNickname = "Ulysses",
                localNicknames = mapOf("peer_1" to "Μαρία"),
                sharedNicknames = mapOf("peer_2" to "Κώστας")
            ),
            seenMessageIds = listOf("msg_1", "msg_2"),
            baselineEstablished = true,
            hasArchive = true,
            archiveByteLength = 123456L,
            archiveSha256Hex = "a".repeat(64)
        )
        val roundtrip = TransferMetadataEnvelope.decode(original.encode())
        assertEquals(original, roundtrip)

        val ack = TransferAckEnvelope(
            ok = true,
            newInstallationId = "inst_xyz",
            importedHistory = true,
            revokedOldInstallations = true,
            errorMessage = null
        )
        assertEquals(ack, TransferAckEnvelope.decode(ack.encode()))
    }

    @Test
    fun test_selectCandidateIpv4Hosts_prioritizesWifiAndFiltersVirtualOrPublic() {
        val candidates = listOf(
            NetworkHostCandidate("lo", "127.0.0.1", isUp = true, isLoopback = true, isVirtual = false, isSiteLocal = false),
            NetworkHostCandidate("tun0", "10.8.0.2", isUp = true, isLoopback = false, isVirtual = true, isSiteLocal = true),
            NetworkHostCandidate("rmnet_data0", "100.64.1.5", isUp = true, isLoopback = false, isVirtual = false, isSiteLocal = false),
            NetworkHostCandidate("eth0", "192.168.100.2", isUp = true, isLoopback = false, isVirtual = false, isSiteLocal = true),
            NetworkHostCandidate("wlan0", "192.168.1.44", isUp = true, isLoopback = false, isVirtual = false, isSiteLocal = true),
            NetworkHostCandidate("swlan0", "192.168.43.1", isUp = true, isLoopback = false, isVirtual = false, isSiteLocal = true),
            NetworkHostCandidate("wlan1", "10.0.0.8", isUp = false, isLoopback = false, isVirtual = false, isSiteLocal = true)
        )

        val selected = LocalNetworkAddressResolver.selectCandidateIpv4Hosts(candidates)
        assertEquals(listOf("192.168.1.44", "192.168.43.1", "192.168.100.2"), selected)
    }
}
