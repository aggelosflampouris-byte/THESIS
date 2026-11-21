package com.example.thesis.transfer

import com.example.thesis.identity.NicknameSnapshot
import com.example.thesis.util.CryptoUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DeviceTransferPairingInfo(
    val hosts: List<String>,
    val port: Int,
    val sessionKey: ByteArray,
    val sasCode: String,
    val uri: String
) {
    fun wipe() {
        sessionKey.fill(0x00)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceTransferPairingInfo) return false
        return hosts == other.hosts &&
            port == other.port &&
            sessionKey.contentEquals(other.sessionKey) &&
            sasCode == other.sasCode &&
            uri == other.uri
    }

    override fun hashCode(): Int {
        var result = hosts.hashCode()
        result = 31 * result + port
        result = 31 * result + sessionKey.contentHashCode()
        result = 31 * result + sasCode.hashCode()
        result = 31 * result + uri.hashCode()
        return result
    }
}

data class TransferMetadataEnvelope(
    val challengeNonce: ByteArray,
    val walletAddress: String,
    val identityPrivateKey: ByteArray,
    val nicknames: NicknameSnapshot,
    val seenMessageIds: List<String>,
    val baselineEstablished: Boolean,
    val hasArchive: Boolean,
    val archiveByteLength: Long,
    val archiveSha256Hex: String?
) {
    fun encode(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            TransferCodecIo.writeBytes(out, challengeNonce)
            TransferCodecIo.writeString(out, walletAddress)
            TransferCodecIo.writeBytes(out, identityPrivateKey)
            TransferCodecIo.writeOptionalString(out, nicknames.myNickname)
            TransferCodecIo.writeStringMap(out, nicknames.localNicknames)
            TransferCodecIo.writeStringMap(out, nicknames.sharedNicknames)
            TransferCodecIo.writeStringList(out, seenMessageIds)
            out.writeBoolean(baselineEstablished)
            out.writeBoolean(hasArchive)
            out.writeLong(archiveByteLength)
            TransferCodecIo.writeOptionalString(out, archiveSha256Hex)
        }
        return baos.toByteArray()
    }

    fun wipe() {
        challengeNonce.fill(0x00)
        identityPrivateKey.fill(0x00)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransferMetadataEnvelope) return false
        return challengeNonce.contentEquals(other.challengeNonce) &&
            walletAddress == other.walletAddress &&
            identityPrivateKey.contentEquals(other.identityPrivateKey) &&
            nicknames == other.nicknames &&
            seenMessageIds == other.seenMessageIds &&
            baselineEstablished == other.baselineEstablished &&
            hasArchive == other.hasArchive &&
            archiveByteLength == other.archiveByteLength &&
            archiveSha256Hex == other.archiveSha256Hex
    }

    override fun hashCode(): Int {
        var result = challengeNonce.contentHashCode()
        result = 31 * result + walletAddress.hashCode()
        result = 31 * result + identityPrivateKey.contentHashCode()
        result = 31 * result + nicknames.hashCode()
        result = 31 * result + seenMessageIds.hashCode()
        result = 31 * result + baselineEstablished.hashCode()
        result = 31 * result + hasArchive.hashCode()
        result = 31 * result + archiveByteLength.hashCode()
        result = 31 * result + (archiveSha256Hex?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun decode(raw: ByteArray): TransferMetadataEnvelope {
            DataInputStream(ByteArrayInputStream(raw)).use { inp ->
                val nonce = TransferCodecIo.readBytes(inp, DeviceTransferProtocol.NONCE_BYTES * 2)
                val wallet = TransferCodecIo.readString(inp, 128)
                val privKey = TransferCodecIo.readBytes(inp, 128)
                val myNick = TransferCodecIo.readOptionalString(inp, 128)
                val localNicks = TransferCodecIo.readStringMap(inp, 2048)
                val sharedNicks = TransferCodecIo.readStringMap(inp, 2048)
                val seenIds = TransferCodecIo.readStringList(inp, 2048)
                val baseline = inp.readBoolean()
                val hasArchive = inp.readBoolean()
                val archiveLen = inp.readLong()
                val archiveHash = TransferCodecIo.readOptionalString(inp, 128)
                return TransferMetadataEnvelope(
                    challengeNonce = nonce,
                    walletAddress = wallet,
                    identityPrivateKey = privKey,
                    nicknames = NicknameSnapshot(
                        myNickname = myNick,
                        localNicknames = localNicks,
                        sharedNicknames = sharedNicks
                    ),
                    seenMessageIds = seenIds,
                    baselineEstablished = baseline,
                    hasArchive = hasArchive,
                    archiveByteLength = archiveLen,
                    archiveSha256Hex = archiveHash
                )
            }
        }
    }
}

data class TransferAckEnvelope(
    val ok: Boolean,
    val newInstallationId: String? = null,
    val importedHistory: Boolean = false,
    val revokedOldInstallations: Boolean = false,
    val errorMessage: String? = null
) {
    fun encode(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeBoolean(ok)
            TransferCodecIo.writeOptionalString(out, newInstallationId)
            out.writeBoolean(importedHistory)
            out.writeBoolean(revokedOldInstallations)
            TransferCodecIo.writeOptionalString(out, errorMessage)
        }
        return baos.toByteArray()
    }

    companion object {
        fun decode(raw: ByteArray): TransferAckEnvelope {
            DataInputStream(ByteArrayInputStream(raw)).use { inp ->
                return TransferAckEnvelope(
                    ok = inp.readBoolean(),
                    newInstallationId = TransferCodecIo.readOptionalString(inp, 256),
                    importedHistory = inp.readBoolean(),
                    revokedOldInstallations = inp.readBoolean(),
                    errorMessage = TransferCodecIo.readOptionalString(inp, 2048)
                )
            }
        }
    }
}

// Serializador binario compacto para evitar dependencia de stubs Android JSON en tests JVM.
private object TransferCodecIo {
    private const val MAX_STRING_BYTES = 16 * 1024

    fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    fun readBytes(inp: DataInputStream, maxLen: Int): ByteArray {
        val len = inp.readInt()
        if (len < 0 || len > maxLen) throw SecurityException("Invalid binary field length: $len")
        return ByteArray(len).also { inp.readFully(it) }
    }

    fun writeString(out: DataOutputStream, value: String) {
        writeBytes(out, value.toByteArray(Charsets.UTF_8))
    }

    fun readString(inp: DataInputStream, maxLen: Int = MAX_STRING_BYTES): String {
        return readBytes(inp, maxLen).toString(Charsets.UTF_8)
    }

    fun writeOptionalString(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) writeString(out, value)
    }

    fun readOptionalString(inp: DataInputStream, maxLen: Int = MAX_STRING_BYTES): String? {
        return if (inp.readBoolean()) readString(inp, maxLen) else null
    }

    fun writeStringList(out: DataOutputStream, items: List<String>) {
        out.writeInt(items.size)
        items.forEach { writeString(out, it) }
    }

    fun readStringList(inp: DataInputStream, maxCount: Int): List<String> {
        val count = inp.readInt()
        if (count < 0 || count > maxCount) throw SecurityException("Invalid list count: $count")
        val out = ArrayList<String>(count)
        repeat(count) { out.add(readString(inp)) }
        return out
    }

    fun writeStringMap(out: DataOutputStream, map: Map<String, String>) {
        out.writeInt(map.size)
        map.forEach { (k, v) ->
            writeString(out, k)
            writeString(out, v)
        }
    }

    fun readStringMap(inp: DataInputStream, maxCount: Int): Map<String, String> {
        val count = inp.readInt()
        if (count < 0 || count > maxCount) throw SecurityException("Invalid map count: $count")
        val out = LinkedHashMap<String, String>(count)
        repeat(count) {
            val k = readString(inp)
            val v = readString(inp)
            out[k] = v
        }
        return out
    }
}

data class DecryptedFrame(
    val frameType: Byte,
    val seq: Long,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecryptedFrame) return false
        return frameType == other.frameType && seq == other.seq && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = frameType.toInt()
        result = 31 * result + seq.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object DeviceTransferProtocol {
    const val SCHEME = "thesis-xfer"
    const val VERSION = "v1"
    val FRAME_MAGIC = byteArrayOf('T'.code.toByte(), 'X'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    const val WIRE_VERSION: Byte = 1

    const val FRAME_HELLO_CHALLENGE: Byte = 0x01
    const val FRAME_METADATA_AND_KEYS: Byte = 0x02
    const val FRAME_ARCHIVE_CHUNK: Byte = 0x03
    const val FRAME_TRANSFER_FINISH: Byte = 0x04
    const val FRAME_IMPORT_ACK: Byte = 0x05

    const val MAX_METADATA_BYTES = 512 * 1024
    const val CHUNK_SIZE_BYTES = 64 * 1024
    const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    const val SESSION_KEY_BYTES = 32
    const val NONCE_BYTES = 16
    const val GCM_IV_BYTES = 12
    const val GCM_TAG_BITS = 128
    const val MAX_HOSTS = 3

    const val LABEL_S2R = "thesis-xfer-v1:s2r"
    const val LABEL_R2S = "thesis-xfer-v1:r2s"
    const val LABEL_ARCHIVE = "thesis-xfer-v1:xmtp-archive"

    fun buildPairingUri(
        hosts: List<String>,
        port: Int,
        sessionKey: ByteArray,
        allowLoopback: Boolean = false
    ): DeviceTransferPairingInfo {
        require(port in 1024..65535) { "Invalid transfer port: $port" }
        require(sessionKey.size == SESSION_KEY_BYTES) { "Expected $SESSION_KEY_BYTES-byte session key" }
        val cleanHosts = hosts.map { it.trim() }
            .filter { isAllowedTransferIpv4(it, allowLoopback) }
            .distinct()
            .take(MAX_HOSTS)
        require(cleanHosts.isNotEmpty()) { "At least one valid local IPv4 host is required" }

        val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(sessionKey)
        val uri = "$SCHEME://$VERSION?h=${cleanHosts.joinToString(",")}&p=$port&k=$encodedKey"
        return DeviceTransferPairingInfo(
            hosts = cleanHosts,
            port = port,
            sessionKey = sessionKey.copyOf(),
            sasCode = computeSasCode(sessionKey),
            uri = uri
        )
    }

    fun parsePairingUri(
        rawUri: String,
        allowLoopback: Boolean = false
    ): DeviceTransferPairingInfo? {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        if (!VERSION.equals(uri.authority, ignoreCase = true)) return null

        val params = parseQueryParams(uri.rawQuery ?: return null)
        val hostsRaw = params["h"] ?: return null
        val port = params["p"]?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
        val keyParam = params["k"] ?: return null

        val sessionKey = runCatching { Base64.getUrlDecoder().decode(keyParam) }.getOrNull() ?: return null
        if (sessionKey.size != SESSION_KEY_BYTES) {
            sessionKey.fill(0x00)
            return null
        }

        val hostParts = hostsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (hostParts.isEmpty() || hostParts.size > MAX_HOSTS) {
            sessionKey.fill(0x00)
            return null
        }
        // Rechazamos cualquier host fuera de RFC1918 para bloquear SSRF desde codigos QR maliciosos.
        if (hostParts.any { !isAllowedTransferIpv4(it, allowLoopback) }) {
            sessionKey.fill(0x00)
            return null
        }
        val cleanHosts = hostParts.distinct()

        return DeviceTransferPairingInfo(
            hosts = cleanHosts,
            port = port,
            sessionKey = sessionKey,
            sasCode = computeSasCode(sessionKey),
            uri = trimmed
        )
    }

    fun computeSasCode(sessionKey: ByteArray): String {
        val prefix = "thesis-xfer-v1-sas|".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(prefix)
            update(sessionKey)
        }.digest()
        val u32 = ((digest[0].toLong() and 0xFFL) shl 24) or
            ((digest[1].toLong() and 0xFFL) shl 16) or
            ((digest[2].toLong() and 0xFFL) shl 8) or
            (digest[3].toLong() and 0xFFL)
        val sixDigit = (u32 % 1_000_000L).toInt()
        return String.format(Locale.US, "%03d %03d", sixDigit / 1000, sixDigit % 1000)
    }

    fun isPrivateRfc1918Ipv4(host: String): Boolean {
        val octets = parseIpv4Octets(host) ?: return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    fun isAllowedTransferIpv4(host: String, allowLoopback: Boolean = false): Boolean {
        val octets = parseIpv4Octets(host) ?: return false
        if (isPrivateRfc1918Ipv4(host)) return true
        return allowLoopback && octets[0] == 127 && octets[1] == 0 && octets[2] == 0 && octets[3] == 1
    }

    private fun parseIpv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            octets[i] = v
        }
        return octets
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
            val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            map[k] = v
        }
        return map
    }
}

object DeviceTransferCrypto {
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

    // Derivamos subclaves independientes por direccion para impedir ataques de reflexion de tramas.
    fun deriveSubkey(sessionKey: ByteArray, label: String): ByteArray {
        require(sessionKey.size == DeviceTransferProtocol.SESSION_KEY_BYTES) {
            "Expected ${DeviceTransferProtocol.SESSION_KEY_BYTES}-byte master session key"
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    fun writeEncryptedFrame(
        out: DataOutputStream,
        key: ByteArray,
        frameType: Byte,
        seq: Long,
        plaintext: ByteArray
    ) {
        val iv = ByteArray(DeviceTransferProtocol.GCM_IV_BYTES)
        CryptoUtils.secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        // Anticipamos la longitud exacta del tag GCM para autenticar la cabecera en el AAD.
        val expectedCipherLen = plaintext.size + (DeviceTransferProtocol.GCM_TAG_BITS / 8)
        val aad = buildAad(frameType, seq, expectedCipherLen)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(DeviceTransferProtocol.GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        out.write(DeviceTransferProtocol.FRAME_MAGIC)
        out.writeByte(DeviceTransferProtocol.WIRE_VERSION.toInt())
        out.writeByte(frameType.toInt())
        out.writeLong(seq)
        out.write(iv)
        out.writeInt(ciphertext.size)
        out.write(ciphertext)
    }

    fun readEncryptedFrame(
        input: DataInputStream,
        key: ByteArray,
        expectedFrameType: Byte?,
        expectedSeq: Long,
        maxPlaintextBytes: Int
    ): DecryptedFrame {
        val magic = ByteArray(DeviceTransferProtocol.FRAME_MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(DeviceTransferProtocol.FRAME_MAGIC)) {
            throw SecurityException("Invalid frame magic header")
        }
        val version = input.readByte()
        if (version != DeviceTransferProtocol.WIRE_VERSION) {
            throw SecurityException("Unsupported frame wire version: $version")
        }
        val frameType = input.readByte()
        if (expectedFrameType != null && frameType != expectedFrameType) {
            throw SecurityException("Unexpected frame type: $frameType (expected $expectedFrameType)")
        }
        val seq = input.readLong()
        if (seq != expectedSeq) {
            throw SecurityException("Unexpected frame sequence: $seq (expected $expectedSeq)")
        }
        val iv = ByteArray(DeviceTransferProtocol.GCM_IV_BYTES)
        input.readFully(iv)

        val tagBytes = DeviceTransferProtocol.GCM_TAG_BITS / 8
        val ciphertextLen = input.readInt()
        if (ciphertextLen < tagBytes || ciphertextLen > maxPlaintextBytes + tagBytes) {
            throw SecurityException("Ciphertext length out of bounds: $ciphertextLen")
        }
        val ciphertext = ByteArray(ciphertextLen)
        input.readFully(ciphertext)

        val aad = buildAad(frameType, seq, ciphertextLen)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(DeviceTransferProtocol.GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ciphertext)
        return DecryptedFrame(frameType = frameType, seq = seq, payload = plaintext)
    }

    private fun buildAad(frameType: Byte, seq: Long, ciphertextLen: Int): ByteArray {
        val baos = ByteArrayOutputStream(18)
        DataOutputStream(baos).use { aadOut ->
            aadOut.write(DeviceTransferProtocol.FRAME_MAGIC)
            aadOut.writeByte(DeviceTransferProtocol.WIRE_VERSION.toInt())
            aadOut.writeByte(frameType.toInt())
            aadOut.writeLong(seq)
            aadOut.writeInt(ciphertextLen)
        }
        return baos.toByteArray()
    }
}

object TransferFileHygiene {
    private const val TRANSFER_CACHE_DIR = "device_transfer"

    fun transferDirectory(cacheDir: File): File = File(cacheDir, TRANSFER_CACHE_DIR)

    fun createTempTransferFile(cacheDir: File, prefix: String): File {
        val dir = transferDirectory(cacheDir)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${prefix}_${UUID.randomUUID()}.bin")
    }

    fun shredAndDelete(file: File?) {
        if (file == null || !file.exists()) return
        runCatching {
            if (file.isFile && file.length() > 0L) {
                RandomAccessFile(file, "rws").use { raf ->
                    raf.setLength(0L)
                }
            }
        }
        runCatching { file.delete() }
    }

    fun purgeTransferCache(cacheDir: File) {
        val dir = transferDirectory(cacheDir)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isFile) shredAndDelete(child) else child.deleteRecursively()
        }
        runCatching { dir.delete() }
    }
}
