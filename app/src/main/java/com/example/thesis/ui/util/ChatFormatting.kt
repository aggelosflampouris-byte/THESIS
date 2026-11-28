package com.example.thesis.ui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Utilidades puras para iniciales de avatar, divisores de fecha y agrupacion visual de burbujas.
internal object ChatFormatting {
    private const val GROUPING_WINDOW_MS: Long = 2 * 60_000L

    fun extractAvatarInitials(displayName: String): String {
        val cleaned = displayName.trim()
        if (cleaned.isEmpty()) return "?"
        if (cleaned.startsWith("0x", ignoreCase = true) && cleaned.length > 3) {
            return cleaned.substring(2, 4).uppercase(Locale.ROOT)
        }
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 2) {
            val first = words[0].firstOrNull()?.uppercaseChar() ?: '?'
            val second = words[1].firstOrNull()?.uppercaseChar() ?: '?'
            return "$first$second"
        }
        val chars = cleaned.filter { it.isLetterOrDigit() }
        return if (chars.isNotEmpty()) {
            chars.take(2).uppercase(Locale.getDefault())
        } else {
            cleaned.take(1).uppercase(Locale.getDefault())
        }
    }

    fun isSameCalendarDay(
        aMs: Long,
        bMs: Long,
        locale: Locale = Locale.getDefault()
    ): Boolean {
        val calA = Calendar.getInstance(locale).apply { timeInMillis = aMs }
        val calB = Calendar.getInstance(locale).apply { timeInMillis = bMs }
        return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
            calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
    }

    fun formatConversationListTime(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault()
    ): String {
        if (timestampMs <= 0L) return ""
        if (isSameCalendarDay(timestampMs, nowMs, locale)) {
            return SimpleDateFormat("HH:mm", locale).format(Date(timestampMs))
        }
        val yesterdayCal = Calendar.getInstance(locale).apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }
        if (isSameCalendarDay(timestampMs, yesterdayCal.timeInMillis, locale)) {
            return "Χθες"
        }
        return SimpleDateFormat("dd/MM", locale).format(Date(timestampMs))
    }

    fun formatChatDateDivider(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault()
    ): String {
        if (timestampMs <= 0L) return ""
        if (isSameCalendarDay(timestampMs, nowMs, locale)) {
            return "Σήμερα"
        }
        val yesterdayCal = Calendar.getInstance(locale).apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }
        if (isSameCalendarDay(timestampMs, yesterdayCal.timeInMillis, locale)) {
            return "Χθες"
        }
        val nowYear = Calendar.getInstance(locale).apply { timeInMillis = nowMs }.get(Calendar.YEAR)
        val msgYear = Calendar.getInstance(locale).apply { timeInMillis = timestampMs }.get(Calendar.YEAR)
        val pattern = if (nowYear == msgYear) "d MMM" else "d MMM yyyy"
        return SimpleDateFormat(pattern, locale).format(Date(timestampMs))
    }

    fun shouldGroupMessages(
        prevSender: String?,
        prevTsMs: Long,
        curSender: String,
        curTsMs: Long,
        locale: Locale = Locale.getDefault()
    ): Boolean {
        if (prevSender.isNullOrBlank() || curSender.isBlank()) return false
        if (!prevSender.equals(curSender, ignoreCase = true)) return false
        if (prevTsMs <= 0L || curTsMs <= 0L) return false
        if (!isSameCalendarDay(prevTsMs, curTsMs, locale)) return false
        return kotlin.math.abs(curTsMs - prevTsMs) <= GROUPING_WINDOW_MS
    }
}

