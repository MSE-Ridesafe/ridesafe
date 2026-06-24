package de.uhi.enia.ridesafe.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeFormatterTest {
    private fun durationOf(
        seconds: Long,
    ): String? = formatDuration(0, seconds * 1000)

    @Test
    fun inProgressRideHasNoDuration() {
        assertNull(formatDuration(0, null))
    }

    @Test
    fun dropsSecondsAndLeadingZeroUnits() {
        assertEquals("0m", durationOf(45)) // under a minute floors to 0m
        assertEquals("5m", durationOf(5 * 60 + 59)) // seconds dropped, not rounded
        assertEquals("1h 05m", durationOf(3600 + 5 * 60))
        assertEquals("2d 03h 04m", durationOf(2 * 86_400 + 3 * 3600 + 4 * 60))
    }
}
