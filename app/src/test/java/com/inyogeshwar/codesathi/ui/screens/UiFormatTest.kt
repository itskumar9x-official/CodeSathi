package com.inyogeshwar.codesathi.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormatTest {
    @Test
    fun elapsedUnderAMinuteShowsSeconds() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("59s", formatElapsed(59))
    }

    @Test
    fun elapsedOverAMinutePadsSeconds() {
        assertEquals("1m 05s", formatElapsed(65))
        assertEquals("12m 00s", formatElapsed(720))
    }

    @Test
    fun relativeTimeBuckets() {
        val now = 1_000_000_000_000L
        assertEquals("just now", relativeTime(now - 10_000, now))
        assertEquals("5 min ago", relativeTime(now - 5 * 60_000, now))
        assertEquals("3 h ago", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2 d ago", relativeTime(now - 2 * 86_400_000L, now))
        // A future timestamp never shows a negative age.
        assertEquals("just now", relativeTime(now + 60_000, now))
    }
}
