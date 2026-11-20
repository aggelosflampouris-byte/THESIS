package com.example.thesis.xmtp

import com.example.thesis.identity.NicknamePolicy
import com.google.protobuf.ByteString
import org.xmtp.android.library.codecs.ContentCodec
import org.xmtp.android.library.codecs.ContentTypeId
import org.xmtp.android.library.codecs.ContentTypeIdBuilder
import org.xmtp.android.library.codecs.EncodedContent

data class ProfileNickname(val nickname: String)

val ContentTypeProfileNickname: ContentTypeId = ContentTypeIdBuilder.builderFromAuthorityId(
    authorityId = "thesis.xmtp.app",
    typeId = "nickname",
    versionMajor = 1,
    versionMinor = 0
)

class ProfileNicknameCodec(
    override var contentType: ContentTypeId = ContentTypeProfileNickname
) : ContentCodec<ProfileNickname> {

    override fun encode(content: ProfileNickname): EncodedContent {
        val sanitized = NicknamePolicy.sanitize(content.nickname).orEmpty()
        return EncodedContent.newBuilder()
            .setType(contentType)
            .putAllParameters(mapOf("encoding" to "UTF-8"))
            .setContent(ByteString.copyFromUtf8(sanitized))
            .build()
    }

    override fun decode(content: EncodedContent): ProfileNickname {
        val decoded = NicknamePolicy.sanitize(content.content.toStringUtf8()).orEmpty()
        return ProfileNickname(decoded)
    }

    override fun fallback(content: ProfileNickname): String? = null

    // Los cambios de metadatos de perfil nunca deben disparar una notificacion emergente al interlocutor.
    override fun shouldPush(content: ProfileNickname): Boolean = false
}
