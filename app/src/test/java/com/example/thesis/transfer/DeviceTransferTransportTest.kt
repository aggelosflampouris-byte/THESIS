package com.example.thesis.transfer

import com.example.thesis.identity.NicknameSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException

class DeviceTransferTransportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_loopbackTransfer_transfersIdentityNicknamesBaselineAndMultiChunkArchive() = runBlocking {
        // Usamos runBlocking con Dispatchers.IO y limite de tiempo porque los sockets bloquean hilos reales.
        withTimeout(15_000L) {
            val tempRoot = tempFolder.newFolder("xfer_root")
            val senderArchive = tempFolder.newFile("sender_archive.bin").apply {
                writeBytes(ByteArray(160 * 1024) { (it % 251).toByte() })
            }
            val expectedArchiveBytes = senderArchive.readBytes()
            val privKey = ByteArray(32) { (it + 10).toByte() }
            val nicknames = NicknameSnapshot(
                myNickname = "Άγγελος",
                localNicknames = mapOf("peer_a" to "Φίλος Α"),
                sharedNicknames = mapOf("peer_b" to "Φίλος Β")
            )
            val seenIds = listOf("msg_1", "msg_2", "msg_3")

            val server = DeviceTransferServer(hosts = listOf("127.0.0.1"), allowLoopbackBind = true)
            val pairing = DeviceTransferProtocol.parsePairingUri(server.pairingInfo.uri, allowLoopback = true)!!
            val progressEvents = Collections.synchronizedList(mutableListOf<Float>())

            var capturedAddress: String? = null
            var capturedKey: ByteArray? = null
            var capturedNicknames: NicknameSnapshot? = null
            var capturedSeenIds: List<String>? = null
            var capturedBaseline = false
            var capturedArchiveBytes: ByteArray? = null

            val serverDeferred = async(Dispatchers.IO) {
                server.acceptAndReceive(
                    tempDir = tempRoot,
                    onProgress = { p, _ -> progressEvents.add(p) },
                    onApplyImport = { bundle ->
                        capturedAddress = bundle.walletAddress
                        capturedKey = bundle.identityPrivateKey.copyOf()
                        capturedNicknames = bundle.nicknames
                        capturedSeenIds = bundle.seenMessageIds
                        capturedBaseline = bundle.baselineEstablished
                        capturedArchiveBytes = bundle.archiveFile?.readBytes()
                        TransferImportResult(
                            ok = true,
                            newInstallationId = "inst_new_01",
                            importedHistory = capturedArchiveBytes != null,
                            revokedOldInstallations = true
                        )
                    }
                )
            }

            val ack = DeviceTransferClient().send(
                pairingInfo = pairing,
                walletAddress = "0x71c7656ec7ab88b098defb751b7401b5f6d8976f",
                identityPrivateKey = privKey,
                nicknames = nicknames,
                seenMessageIds = seenIds,
                baselineEstablished = true,
                archiveFile = senderArchive,
                onProgress = { _, _ -> }
            )
            val serverResult = serverDeferred.await()

            assertTrue(ack.ok)
            assertTrue(serverResult.ok)
            assertEquals("inst_new_01", ack.newInstallationId)
            assertTrue(ack.importedHistory)
            assertTrue(ack.revokedOldInstallations)
            assertEquals("0x71c7656ec7ab88b098defb751b7401b5f6d8976f", capturedAddress)
            assertArrayEquals(privKey, capturedKey)
            assertEquals(nicknames, capturedNicknames)
            assertEquals(seenIds, capturedSeenIds)
            assertTrue(capturedBaseline)
            assertArrayEquals(expectedArchiveBytes, capturedArchiveBytes)
            assertTrue(progressEvents.any { it in 0.15f..0.85f })
            assertTrue(TransferFileHygiene.transferDirectory(tempRoot).listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun test_loopbackTransfer_wrongSessionKeyFailsChallengeAndSkipsImport() = runBlocking {
        withTimeout(15_000L) {
            val tempRoot = tempFolder.newFolder("xfer_wrong_key")
            val server = DeviceTransferServer(hosts = listOf("127.0.0.1"), allowLoopbackBind = true)
            val parsed = DeviceTransferProtocol.parsePairingUri(server.pairingInfo.uri, allowLoopback = true)!!
            val tamperedKey = parsed.sessionKey.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
            val badPairing = parsed.copy(sessionKey = tamperedKey)
            val importCalled = AtomicBoolean(false)

            val serverDeferred = async(Dispatchers.IO) {
                runCatching {
                    server.acceptAndReceive(
                        tempDir = tempRoot,
                        onProgress = { _, _ -> },
                        onApplyImport = {
                            importCalled.set(true)
                            TransferImportResult(ok = true)
                        }
                    )
                }
            }

            val clientErr = runCatching {
                DeviceTransferClient().send(
                    pairingInfo = badPairing,
                    walletAddress = "0x71c7656ec7ab88b098defb751b7401b5f6d8976f",
                    identityPrivateKey = ByteArray(32) { 1 },
                    nicknames = NicknameSnapshot(),
                    seenMessageIds = emptyList(),
                    baselineEstablished = false,
                    archiveFile = null,
                    onProgress = { _, _ -> }
                )
            }.exceptionOrNull()

            server.cancel()
            serverDeferred.await()

            assertTrue(clientErr is AEADBadTagException)
            assertFalse(importCalled.get())
        }
    }

    @Test
    fun test_loopbackTransfer_corruptedArchiveFilePreventsApplyImport() = runBlocking {
        withTimeout(15_000L) {
            val tempRoot = tempFolder.newFolder("xfer_bad_digest")
            val sourceFile = tempFolder.newFile("bad_archive.bin").apply {
                writeBytes(ByteArray(4096) { 0x55 })
            }

            val server = DeviceTransferServer(hosts = listOf("127.0.0.1"), allowLoopbackBind = true)
            val pairing = DeviceTransferProtocol.parsePairingUri(server.pairingInfo.uri, allowLoopback = true)!!
            val importCalled = AtomicBoolean(false)

            // Simulamos un emisor con un hash declarado manipulado en la cabecera.
            val s2r = DeviceTransferCrypto.deriveSubkey(pairing.sessionKey, DeviceTransferProtocol.LABEL_S2R)
            val r2s = DeviceTransferCrypto.deriveSubkey(pairing.sessionKey, DeviceTransferProtocol.LABEL_R2S)

            val serverDeferred = async(Dispatchers.IO) {
                runCatching {
                    server.acceptAndReceive(
                        tempDir = tempRoot,
                        onProgress = { _, _ -> },
                        onApplyImport = {
                            importCalled.set(true)
                            TransferImportResult(ok = true)
                        }
                    )
                }
            }

            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", pairing.port).use { socket ->
                    socket.soTimeout = 10_000
                    val inp = DataInputStream(socket.getInputStream())
                    val out = DataOutputStream(socket.getOutputStream())
                    val challenge = DeviceTransferCrypto.readEncryptedFrame(
                        inp,
                        r2s,
                        DeviceTransferProtocol.FRAME_HELLO_CHALLENGE,
                        0L,
                        DeviceTransferProtocol.NONCE_BYTES
                    )
                    val bogusMeta = TransferMetadataEnvelope(
                        challengeNonce = challenge.payload,
                        walletAddress = "0x71c7656ec7ab88b098defb751b7401b5f6d8976f",
                        identityPrivateKey = ByteArray(32) { 1 },
                        nicknames = NicknameSnapshot(),
                        seenMessageIds = emptyList(),
                        baselineEstablished = true,
                        hasArchive = true,
                        archiveByteLength = sourceFile.length(),
                        archiveSha256Hex = "0".repeat(64)
                    )
                    DeviceTransferCrypto.writeEncryptedFrame(
                        out,
                        s2r,
                        DeviceTransferProtocol.FRAME_METADATA_AND_KEYS,
                        0L,
                        bogusMeta.encode()
                    )
                    DeviceTransferCrypto.writeEncryptedFrame(
                        out,
                        s2r,
                        DeviceTransferProtocol.FRAME_ARCHIVE_CHUNK,
                        1L,
                        sourceFile.readBytes()
                    )
                    val finishOut = ByteArrayOutputStream()
                    DataOutputStream(finishOut).use { fout ->
                        fout.writeLong(sourceFile.length())
                        val h = "0".repeat(64).toByteArray(Charsets.UTF_8)
                        fout.writeInt(h.size)
                        fout.write(h)
                    }
                    DeviceTransferCrypto.writeEncryptedFrame(
                        out,
                        s2r,
                        DeviceTransferProtocol.FRAME_TRANSFER_FINISH,
                        2L,
                        finishOut.toByteArray()
                    )
                    out.flush()
                }
            }

            val serverOutcome = serverDeferred.await()
            assertTrue(serverOutcome.exceptionOrNull() is SecurityException)
            assertFalse(importCalled.get())
        }
    }
}
