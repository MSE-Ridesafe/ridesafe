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
        assertEquals("0 min", durationOf(45)) // under a minute floors to 0 min
        assertEquals("5 min", durationOf(5 * 60 + 59)) // seconds dropped, not rounded
        assertEquals("1 h 05 min", durationOf(3600 + 5 * 60))
        assertEquals("2 d 03 h 04 min", durationOf(2 * 86_400 + 3 * 3600 + 4 * 60))
    }
}
