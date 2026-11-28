package com.example.thesis.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class ChatFormattingTest {

    @Test
    fun test_extractAvatarInitials_handlesNamesHexAndEmpty() {
        assertEquals("ΓΠ", ChatFormatting.extractAvatarInitials("Γιώργος Παπαδόπουλος"))
        assertEquals("ΜΑ", ChatFormatting.extractAvatarInitials("Μαρία"))
        assertEquals("F2", ChatFormatting.extractAvatarInitials("0xf2e7550ebed0c000"))
        assertEquals("?", ChatFormatting.extractAvatarInitials("   "))
    }

    @Test
    fun test_dateFormattingAndCalendarDayChecks() {
        val locale = Locale.forLanguageTag("el-GR")
        val baseCal = Calendar.getInstance(locale).apply {
            set(2026, Calendar.NOVEMBER, 28, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMs = baseCal.timeInMillis

        val sameDayMs = (baseCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 5)
        }.timeInMillis
        val yesterdayMs = (baseCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        val olderMs = (baseCal.clone() as Calendar).apply {
            set(Calendar.MONTH, Calendar.NOVEMBER)
            set(Calendar.DAY_OF_MONTH, 15)
        }.timeInMillis

        assertTrue(ChatFormatting.isSameCalendarDay(sameDayMs, nowMs, locale))
        assertFalse(ChatFormatting.isSameCalendarDay(yesterdayMs, nowMs, locale))

        assertEquals("09:05", ChatFormatting.formatConversationListTime(sameDayMs, nowMs, locale))
        assertEquals("Χθες", ChatFormatting.formatConversationListTime(yesterdayMs, nowMs, locale))
        assertEquals("15/11", ChatFormatting.formatConversationListTime(olderMs, nowMs, locale))

        assertEquals("Σήμερα", ChatFormatting.formatChatDateDivider(sameDayMs, nowMs, locale))
        assertEquals("Χθες", ChatFormatting.formatChatDateDivider(yesterdayMs, nowMs, locale))
    }

    @Test
    fun test_shouldGroupMessages_respectsSenderDayAndWindow() {
        val t0 = 1_764_320_000_000L
        assertTrue(ChatFormatting.shouldGroupMessages("alice", t0, "alice", t0 + 45_000L))
        assertFalse(ChatFormatting.shouldGroupMessages("alice", t0, "bob", t0 + 45_000L))
        assertFalse(ChatFormatting.shouldGroupMessages("alice", t0, "alice", t0 + 5 * 60_000L))
        assertFalse(ChatFormatting.shouldGroupMessages(null, t0, "alice", t0))
    }
}

