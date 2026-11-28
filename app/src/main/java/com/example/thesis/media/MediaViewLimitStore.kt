package com.example.thesis.media

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Politica centralizada para medios temporales estilo vista limitada (ej. 1x, 2x, 3x).
object MediaViewLimitPolicy {
    const val UNLIMITED_VIEWS: Int = 0
    const val DEFAULT_TEMPORARY_VIEWS: Int = 2
    const val MAX_VIEW_LIMIT: Int = 10
    val PRESET_VIEW_LIMITS: List<Int> = listOf(0, 1, 2, 3)

    fun sanitizeMaxViews(raw: Int): Int = raw.coerceIn(UNLIMITED_VIEWS, MAX_VIEW_LIMIT)

    fun remainingViews(maxViews: Int, openedCount: Int): Int {
        if (maxViews <= UNLIMITED_VIEWS) return Int.MAX_VALUE
        val safeOpened = openedCount.coerceAtLeast(0)
        return (maxViews - safeOpened).coerceAtLeast(0)
    }

    fun isExpired(maxViews: Int, openedCount: Int): Boolean {
        return maxViews > UNLIMITED_VIEWS && openedCount >= maxViews
    }

    fun canOpen(maxViews: Int, openedCount: Int): Boolean = !isExpired(maxViews, openedCount)

    fun formatPresetLabel(maxViews: Int): String {
        return if (maxViews <= UNLIMITED_VIEWS) "Κανονικό" else "${maxViews}×"
    }

    fun formatRemainingSubtitle(maxViews: Int, openedCount: Int): String {
        val remaining = remainingViews(maxViews, openedCount)
        return if (remaining == 1) {
            "Απομένει 1/$maxViews προβολή"
        } else {
            "Απομένουν $remaining/$maxViews προβολές"
        }
    }

    fun formatExpiredSubtitle(maxViews: Int): String {
        return if (maxViews == 1) {
            "Έληξε • Ανοίχτηκε 1/1 φορά"
        } else {
            "Έληξε • Ανοίχτηκε $maxViews/$maxViews φορές"
        }
    }

    fun formatViewerBanner(viewNumber: Int, maxViews: Int): String {
        val boundedView = viewNumber.coerceIn(1, maxViews.coerceAtLeast(1))
        return if (boundedView >= maxViews) {
            "Τελευταία προβολή ($boundedView/$maxViews) • Προστασία οθόνης"
        } else {
            "Προσωρινό μέσο • Προβολή $boundedView/$maxViews • Προστασία οθόνης"
        }
    }
}

internal object MediaViewLimitCodec {
    fun serializeMap(map: Map<String, Int>): String {
        return map.entries
            .asSequence()
            .mapNotNull { (rawKey, count) ->
                val cleanKey = rawKey.trim()
                    .replace("\t", "")
                    .replace("\n", "")
                    .replace("\r", "")
                if (cleanKey.isEmpty() || count <= 0) null else "$cleanKey\t$count"
            }
            .sorted()
            .joinToString("\n")
    }

    fun deserializeMap(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (line in raw.split('\n')) {
            val tabIdx = line.indexOf('\t')
            if (tabIdx <= 0) continue
            val key = line.substring(0, tabIdx).trim()
            val count = line.substring(tabIdx + 1).trim().toIntOrNull() ?: continue
            if (key.isNotEmpty() && count > 0) {
                result[key] = count
            }
        }
        return result
    }
}

interface MediaViewLimitStorage {
    val openedCounts: StateFlow<Map<String, Int>>
    fun openedCount(messageId: String): Int
    fun recordView(messageId: String, maxViews: Int): Int?
    fun clear()
}

internal class InMemoryMediaViewLimitStore(
    initialCounts: Map<String, Int> = emptyMap()
) : MediaViewLimitStorage {
    private val lock = Any()
    private val _openedCounts = MutableStateFlow(initialCounts.filter { it.key.isNotBlank() && it.value > 0 })
    override val openedCounts: StateFlow<Map<String, Int>> = _openedCounts.asStateFlow()

    override fun openedCount(messageId: String): Int {
        if (messageId.isBlank()) return 0
        return _openedCounts.value[messageId] ?: 0
    }

    override fun recordView(messageId: String, maxViews: Int): Int? {
        if (messageId.isBlank() || maxViews <= MediaViewLimitPolicy.UNLIMITED_VIEWS) return null
        synchronized(lock) {
            val current = _openedCounts.value[messageId] ?: 0
            if (current >= maxViews) return null
            val next = (current + 1).coerceAtMost(maxViews)
            _openedCounts.update { it + (messageId to next) }
            return next
        }
    }

    override fun clear() {
        synchronized(lock) {
            _openedCounts.value = emptyMap()
        }
    }
}

// Almacenamiento sincronizado y persistente de visualizaciones para impedir reinicios tras cerrar la app.
internal class SharedPrefsMediaViewLimitStore(context: Context) : MediaViewLimitStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _openedCounts = MutableStateFlow(
        MediaViewLimitCodec.deserializeMap(prefs.getString(KEY_OPENED_COUNTS, null))
    )
    override val openedCounts: StateFlow<Map<String, Int>> = _openedCounts.asStateFlow()

    override fun openedCount(messageId: String): Int {
        if (messageId.isBlank()) return 0
        return _openedCounts.value[messageId] ?: 0
    }

    @SuppressLint("ApplySharedPref")
    override fun recordView(messageId: String, maxViews: Int): Int? {
        if (messageId.isBlank() || maxViews <= MediaViewLimitPolicy.UNLIMITED_VIEWS) return null
        synchronized(lock) {
            val current = _openedCounts.value[messageId] ?: 0
            if (current >= maxViews) return null
            val next = (current + 1).coerceAtMost(maxViews)
            val updated = _openedCounts.value + (messageId to next)
            val serialized = MediaViewLimitCodec.serializeMap(updated)
            // Usamos commit() sincrono para evitar que el usuario cierre la app antes de persistir el consumo.
            prefs.edit().putString(KEY_OPENED_COUNTS, serialized).commit()
            _openedCounts.value = updated
            return next
        }
    }

    @SuppressLint("ApplySharedPref")
    override fun clear() {
        synchronized(lock) {
            prefs.edit().clear().commit()
            _openedCounts.value = emptyMap()
        }
    }

    companion object {
        private const val PREFS_NAME = "thesis_media_view_limits"
        private const val KEY_OPENED_COUNTS = "opened_counts"
    }
}

