package com.example.thesis.notifications

import android.content.Context

interface SeenMessageStorage {
    fun loadSeenIds(): List<String>
    fun saveSeenIds(ids: List<String>)
    fun isBaselineEstablished(): Boolean
    fun setBaselineEstablished(established: Boolean)
    fun clear()

    companion object {
        const val MAX_PERSISTED_IDS: Int = 512
    }
}

class InMemorySeenMessageStorage(
    private val maxSize: Int = SeenMessageStorage.MAX_PERSISTED_IDS
) : SeenMessageStorage {

    private val lock = Any()
    private var seenIds: List<String> = emptyList()
    private var baselineEstablished: Boolean = false

    override fun loadSeenIds(): List<String> = synchronized(lock) { seenIds }

    override fun saveSeenIds(ids: List<String>) {
        synchronized(lock) {
            seenIds = NotificationStateStoreCodec.normalizeIds(ids, maxSize)
        }
    }

    override fun isBaselineEstablished(): Boolean = synchronized(lock) { baselineEstablished }

    override fun setBaselineEstablished(established: Boolean) {
        synchronized(lock) {
            baselineEstablished = established
        }
    }

    override fun clear() {
        synchronized(lock) {
            seenIds = emptyList()
            baselineEstablished = false
        }
    }
}

internal object NotificationStateStoreCodec {
    const val MAX_PERSISTED_IDS: Int = SeenMessageStorage.MAX_PERSISTED_IDS

    fun normalizeIds(ids: Iterable<String>, maxSize: Int = MAX_PERSISTED_IDS): List<String> {
        if (maxSize <= 0) return emptyList()
        val ordered = LinkedHashSet<String>()
        for (raw in ids) {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) continue
            // Reinsertamos los identificadores repetidos al final para conservar el orden de aparicion mas reciente antes de recortar.
            ordered.remove(cleaned)
            ordered.add(cleaned)
        }
        val list = ordered.toList()
        return if (list.size <= maxSize) list else list.takeLast(maxSize)
    }

    fun serializeSeenIds(ids: Collection<String>, maxSize: Int = MAX_PERSISTED_IDS): String {
        return normalizeIds(ids, maxSize).joinToString("\n")
    }

    fun deserializeSeenIds(raw: String?, maxSize: Int = MAX_PERSISTED_IDS): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return normalizeIds(raw.split('\n'), maxSize)
    }
}

internal class SharedPrefsNotificationStateStore(
    context: Context,
    private val maxSize: Int = NotificationStateStoreCodec.MAX_PERSISTED_IDS
) : SeenMessageStorage {

    private val appContext: Context = context.applicationContext
    private val lock = Any()

    companion object {
        private const val PREF_NAME = "thesis_notification_state"
        private const val KEY_SEEN_IDS = "seen_message_ids"
        private const val KEY_BASELINE_ESTABLISHED = "baseline_established"
    }

    private fun prefs() = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override fun loadSeenIds(): List<String> = synchronized(lock) {
        NotificationStateStoreCodec.deserializeSeenIds(
            prefs().getString(KEY_SEEN_IDS, null),
            maxSize
        )
    }

    override fun saveSeenIds(ids: List<String>) {
        synchronized(lock) {
            val serialized = NotificationStateStoreCodec.serializeSeenIds(ids, maxSize)
            // Usamos commit sincrono para no perder IDs si el sistema congela el proceso justo tras finalizar el worker.
            prefs().edit().putString(KEY_SEEN_IDS, serialized).commit()
        }
    }

    override fun isBaselineEstablished(): Boolean = synchronized(lock) {
        prefs().getBoolean(KEY_BASELINE_ESTABLISHED, false)
    }

    override fun setBaselineEstablished(established: Boolean) {
        synchronized(lock) {
            prefs().edit().putBoolean(KEY_BASELINE_ESTABLISHED, established).commit()
        }
    }

    override fun clear() {
        synchronized(lock) {
            prefs().edit().clear().commit()
            runCatching { appContext.deleteSharedPreferences(PREF_NAME) }
        }
    }
}
