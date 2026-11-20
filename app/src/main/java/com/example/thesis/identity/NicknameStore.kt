package com.example.thesis.identity

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class NicknameSnapshot(
    val myNickname: String? = null,
    val localNicknames: Map<String, String> = emptyMap(),
    val sharedNicknames: Map<String, String> = emptyMap()
)

interface NicknameStorage {
    val snapshot: StateFlow<NicknameSnapshot>
    fun setMyNickname(nickname: String?): String?
    fun setLocalContactNickname(key: String, nickname: String?): String?
    fun setSharedPeerNickname(peerInboxId: String, nickname: String?): String?
    fun clear()
}

object NicknamePolicy {
    const val MAX_NICKNAME_LENGTH: Int = 32

    fun normalizeKey(rawKey: String): String = rawKey.trim().lowercase(Locale.ROOT)

    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        // Sustituimos caracteres de control ISO por espacios para evitar saltos o secuencias ocultas en la interfaz.
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                append(if (ch.isISOControl()) ' ' else ch)
            }
        }.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= MAX_NICKNAME_LENGTH) {
            cleaned
        } else {
            cleaned.take(MAX_NICKNAME_LENGTH).trimEnd()
        }
    }

    fun resolveContactNickname(key: String, snapshot: NicknameSnapshot): String? {
        val normalized = normalizeKey(key)
        if (normalized.isEmpty()) return null
        return snapshot.localNicknames[normalized] ?: snapshot.sharedNicknames[normalized]
    }

    fun resolveConversationTitle(
        conversationKey: String,
        isDm: Boolean,
        isLoopback: Boolean,
        groupName: String?,
        fallbackMaskedPeer: String,
        snapshot: NicknameSnapshot
    ): String {
        val normalizedKey = normalizeKey(conversationKey)
        val localOverride = if (normalizedKey.isNotEmpty()) snapshot.localNicknames[normalizedKey] else null
        if (!localOverride.isNullOrBlank()) return localOverride

        if (isDm && normalizedKey.isNotEmpty()) {
            val sharedPeer = snapshot.sharedNicknames[normalizedKey]
            if (!sharedPeer.isNullOrBlank()) return sharedPeer
        }

        if (isLoopback) return "Loopback MLS (1-Device Test)"

        val cleanedGroup = groupName?.trim().orEmpty()
        if (cleanedGroup.isNotEmpty()) return cleanedGroup

        return if (isDm) "DM: $fallbackMaskedPeer" else fallbackMaskedPeer
    }

    fun resolveNotificationSenderTitle(
        senderInboxId: String,
        conversationId: String,
        groupName: String?,
        snapshot: NicknameSnapshot
    ): String? {
        val normalizedSender = normalizeKey(senderInboxId)
        val normalizedConv = normalizeKey(conversationId)
        return (if (normalizedSender.isNotEmpty()) snapshot.localNicknames[normalizedSender] else null)
            ?: (if (normalizedConv.isNotEmpty()) snapshot.localNicknames[normalizedConv] else null)
            ?: (if (normalizedSender.isNotEmpty()) snapshot.sharedNicknames[normalizedSender] else null)
            ?: groupName?.takeIf { it.isNotBlank() }
    }
}

internal object NicknameStoreCodec {
    fun serializeMap(map: Map<String, String>): String {
        return map.entries
            .asSequence()
            .mapNotNull { (rawKey, rawVal) ->
                val cleanKey = NicknamePolicy.normalizeKey(rawKey)
                    .replace("\t", "")
                    .replace("\n", "")
                    .replace("\r", "")
                val cleanVal = NicknamePolicy.sanitize(rawVal)
                if (cleanKey.isEmpty() || cleanVal == null) null else "$cleanKey\t$cleanVal"
            }
            .sorted()
            .joinToString("\n")
    }

    fun deserializeMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (line in raw.split('\n')) {
            val tabIdx = line.indexOf('\t')
            if (tabIdx <= 0) continue
            val key = NicknamePolicy.normalizeKey(line.substring(0, tabIdx))
            val value = NicknamePolicy.sanitize(line.substring(tabIdx + 1))
            if (key.isNotEmpty() && value != null) {
                result[key] = value
            }
        }
        return result
    }
}

class InMemoryNicknameStorage : NicknameStorage {

    private val lock = Any()
    private val _snapshot = MutableStateFlow(NicknameSnapshot())
    override val snapshot: StateFlow<NicknameSnapshot> = _snapshot.asStateFlow()

    override fun setMyNickname(nickname: String?): String? {
        val clean = NicknamePolicy.sanitize(nickname)
        synchronized(lock) {
            _snapshot.update { it.copy(myNickname = clean) }
        }
        return clean
    }

    override fun setLocalContactNickname(key: String, nickname: String?): String? {
        val normKey = NicknamePolicy.normalizeKey(key)
        if (normKey.isEmpty()) return null
        val clean = NicknamePolicy.sanitize(nickname)
        synchronized(lock) {
            _snapshot.update { current ->
                val updatedMap = if (clean != null) {
                    current.localNicknames + (normKey to clean)
                } else {
                    current.localNicknames - normKey
                }
                current.copy(localNicknames = updatedMap)
            }
        }
        return clean
    }

    override fun setSharedPeerNickname(peerInboxId: String, nickname: String?): String? {
        val normKey = NicknamePolicy.normalizeKey(peerInboxId)
        if (normKey.isEmpty()) return null
        val clean = NicknamePolicy.sanitize(nickname)
        synchronized(lock) {
            _snapshot.update { current ->
                val updatedMap = if (clean != null) {
                    current.sharedNicknames + (normKey to clean)
                } else {
                    current.sharedNicknames - normKey
                }
                current.copy(sharedNicknames = updatedMap)
            }
        }
        return clean
    }

    override fun clear() {
        synchronized(lock) {
            _snapshot.value = NicknameSnapshot()
        }
    }
}

internal class SharedPrefsNicknameStore(context: Context) : NicknameStorage {

    private val appContext: Context = context.applicationContext
    private val lock = Any()

    companion object {
        private const val PREF_NAME = "thesis_nicknames"
        private const val KEY_MY_NICKNAME = "my_nickname"
        private const val KEY_LOCAL_NICKNAMES = "local_nicknames"
        private const val KEY_SHARED_NICKNAMES = "shared_nicknames"
    }

    private fun prefs() = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(loadInitialSnapshot())
    override val snapshot: StateFlow<NicknameSnapshot> = _snapshot.asStateFlow()

    private fun loadInitialSnapshot(): NicknameSnapshot = synchronized(lock) {
        val p = prefs()
        NicknameSnapshot(
            myNickname = NicknamePolicy.sanitize(p.getString(KEY_MY_NICKNAME, null)),
            localNicknames = NicknameStoreCodec.deserializeMap(p.getString(KEY_LOCAL_NICKNAMES, null)),
            sharedNicknames = NicknameStoreCodec.deserializeMap(p.getString(KEY_SHARED_NICKNAMES, null))
        )
    }

    override fun setMyNickname(nickname: String?): String? {
        val clean = NicknamePolicy.sanitize(nickname)
        synchronized(lock) {
            // Confirmamos de forma sincrona para asegurar persistencia ante reinicios o GDPR shred inmediato.
            prefs().edit().apply {
                if (clean != null) putString(KEY_MY_NICKNAME, clean) else remove(KEY_MY_NICKNAME)
            }.commit()
            _snapshot.update { it.copy(myNickname = clean) }
        }
        return clean
    }

    override fun setLocalContactNickname(key: String, nickname: String?): String? {
        val normKey = NicknamePolicy.normalizeKey(key)
        if (normKey.isEmpty()) return null
        val clean = NicknamePolicy.sanitize(nickname)
        synchronized(lock) {
            val currentMap = _snapshot.value.localNicknames
            val updatedMap = if (clean != null) currentMap + (normKey to clean) else currentMap - normKey
            prefs().edit().putString(KEY_LOCAL_NICKNAMES, NicknameStoreCodec.serializeMap(updatedMap)).commit()
            _snapshot.update { it.copy(localNicknames = updatedMap) }
        }
        return clean
    }

    override fun setSharedPeerNickname(peerInboxId: String, nickname: String?): String? {
        val normKey = NicknamePolicy.normalizeKey(peerInboxId)
        if (normKey.isEmpty()) return null
        val clean = NicknamePolicy.sanitize(nickname)
        synchronized(lock) {
            val currentMap = _snapshot.value.sharedNicknames
            val updatedMap = if (clean != null) currentMap + (normKey to clean) else currentMap - normKey
            prefs().edit().putString(KEY_SHARED_NICKNAMES, NicknameStoreCodec.serializeMap(updatedMap)).commit()
            _snapshot.update { it.copy(sharedNicknames = updatedMap) }
        }
        return clean
    }

    override fun clear() {
        synchronized(lock) {
            prefs().edit().clear().commit()
            runCatching { appContext.deleteSharedPreferences(PREF_NAME) }
            _snapshot.value = NicknameSnapshot()
        }
    }
}
