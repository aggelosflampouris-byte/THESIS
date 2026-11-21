package com.example.thesis.xmtp

import com.google.protobuf.ByteString
import org.xmtp.android.library.codecs.ContentCodec
import org.xmtp.android.library.codecs.ContentTypeIdBuilder
import org.xmtp.proto.message.contents.Content.ContentTypeId
import org.xmtp.proto.message.contents.Content.EncodedContent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

val ContentTypeCallSignal: ContentTypeId = ContentTypeIdBuilder.builderFromAuthorityId(
    authorityId = "thesis.xmtp.app",
    typeId = "call_signal",
    versionMajor = 1,
    versionMinor = 0
)

enum class CallSignalType(val wireCode: Int) {
    OFFER(1),
    ANSWER(2),
    ICE_BATCH(3),
    HANGUP(4),
    REJECT(5),
    BUSY(6);

    companion object {
        fun fromWireCode(code: Int): CallSignalType? = entries.firstOrNull { it.wireCode == code }
    }
}

data class CallIceCandidate(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
)

data class CallSignal(
    val version: Int = CallSignalCodec.WIRE_VERSION,
    val callId: String,
    val type: CallSignalType,
    val timestampMs: Long,
    val sdp: String? = null,
    val iceCandidates: List<CallIceCandidate> = emptyList(),
    val reason: String? = null
)

// Codec binario puro para JVM sin dependencias de org.json o android.util.Base64 en tests unitarios.
class CallSignalCodec : ContentCodec<CallSignal> {
    override val contentType: ContentTypeId = ContentTypeCallSignal

    override fun encode(content: CallSignal): EncodedContent {
        val raw = encodeBytes(content)
        return EncodedContent.newBuilder()
            .setType(contentType)
            .setContent(ByteString.copyFrom(raw))
            .build()
    }

    override fun decode(content: EncodedContent): CallSignal {
        return decodeBytes(content.content.toByteArray())
    }

    // No generamos texto de respaldo ni push para evitar mostrar SDP o ICE en el historial del chat.
    override fun fallback(content: CallSignal): String? = null

    override fun shouldPush(content: CallSignal): Boolean = false

    companion object {
        private const val MAGIC_0: Byte = 0x54 // 'T'
        private const val MAGIC_1: Byte = 0x43 // 'C'
        const val WIRE_VERSION: Int = 1
        private const val MAX_STRING_BYTES: Int = 512 * 1024
        private const val MAX_ICE_CANDIDATES: Int = 256

        fun encodeBytes(signal: CallSignal): ByteArray {
            require(signal.callId.isNotBlank()) { "callId cannot be blank" }
            require(signal.iceCandidates.size <= MAX_ICE_CANDIDATES) {
                "Too many ICE candidates in single batch: ${signal.iceCandidates.size}"
            }
            val baos = ByteArrayOutputStream(512)
            DataOutputStream(baos).use { out ->
                out.writeByte(MAGIC_0.toInt())
                out.writeByte(MAGIC_1.toInt())
                out.writeInt(signal.version)
                writeUtf8(out, signal.callId)
                out.writeInt(signal.type.wireCode)
                out.writeLong(signal.timestampMs)

                val sdp = signal.sdp
                out.writeBoolean(sdp != null)
                if (sdp != null) {
                    writeUtf8(out, sdp)
                }

                out.writeInt(signal.iceCandidates.size)
                for (cand in signal.iceCandidates) {
                    writeUtf8(out, cand.sdpMid)
                    out.writeInt(cand.sdpMLineIndex)
                    writeUtf8(out, cand.candidate)
                }

                val reason = signal.reason
                out.writeBoolean(reason != null)
                if (reason != null) {
                    writeUtf8(out, reason)
                }
                out.flush()
            }
            return baos.toByteArray()
        }

        fun decodeBytes(bytes: ByteArray): CallSignal {
            require(bytes.size >= 16) { "Payload too short for CallSignal" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val m0 = input.readByte()
                val m1 = input.readByte()
                require(m0 == MAGIC_0 && m1 == MAGIC_1) { "Invalid CallSignal header magic" }

                val version = input.readInt()
                require(version == WIRE_VERSION) { "Unsupported CallSignal wire version: $version" }

                val callId = readUtf8(input)
                require(callId.isNotBlank()) { "Decoded CallSignal has blank callId" }

                val wireCode = input.readInt()
                val type = CallSignalType.fromWireCode(wireCode)
                    ?: throw IllegalArgumentException("Unknown CallSignal type code: $wireCode")

                val timestampMs = input.readLong()

                val hasSdp = input.readBoolean()
                val sdp = if (hasSdp) readUtf8(input) else null

                val candCount = input.readInt()
                require(candCount in 0..MAX_ICE_CANDIDATES) {
                    "Invalid ICE candidate count: $candCount"
                }
                val iceCandidates = if (candCount == 0) {
                    emptyList()
                } else {
                    List(candCount) {
                        val sdpMid = readUtf8(input)
                        val mLineIndex = input.readInt()
                        val candLine = readUtf8(input)
                        CallIceCandidate(
                            sdpMid = sdpMid,
                            sdpMLineIndex = mLineIndex,
                            candidate = candLine
                        )
                    }
                }

                val hasReason = input.readBoolean()
                val reason = if (hasReason) readUtf8(input) else null

                return CallSignal(
                    version = version,
                    callId = callId,
                    type = type,
                    timestampMs = timestampMs,
                    sdp = sdp,
                    iceCandidates = iceCandidates,
                    reason = reason
                )
            }
        }

        private fun writeUtf8(out: DataOutputStream, value: String) {
            // Escribimos longitud explicita de 32 bits porque writeUTF falla si el SDP supera 64 KiB.
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_STRING_BYTES) { "Encoded string exceeds max allowed length: ${bytes.size}" }
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun readUtf8(input: DataInputStream): String {
            val len = input.readInt()
            require(len in 0..MAX_STRING_BYTES) { "Invalid UTF-8 field length: $len" }
            if (len == 0) return ""
            val buf = ByteArray(len)
            input.readFully(buf)
            return String(buf, StandardCharsets.UTF_8)
        }
    }
}
