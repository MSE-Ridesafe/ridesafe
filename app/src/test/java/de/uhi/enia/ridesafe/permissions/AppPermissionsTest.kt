package de.uhi.enia.ridesafe.permissions

import de.uhi.enia.ridesafe.recording.trigger.AutoTrackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsTest {
    @Test
    fun `off demands nothing`() {
        assertEquals(emptyList<AppPermission>(), requiredFor(AutoTrackMode.OFF))
    }

    @Test
    fun `activity recognition is only needed for any-vehicle mode`() {
        assertFalse(AppPermission.ACTIVITY in requiredFor(AutoTrackMode.PAIRED_ONLY))
        assertTrue(AppPermission.ACTIVITY in requiredFor(AutoTrackMode.ANY))
    }

    @Test
    fun `every tracking mode needs location and bluetooth`() {
        listOf(AutoTrackMode.PAIRED_ONLY, AutoTrackMode.ANY).forEach { mode ->
            assertTrue(AppPermission.LOCATION in requiredFor(mode))
            assertTrue(AppPermission.BLUETOOTH in requiredFor(mode))
        }
    }

    /** Android drops the whole request if background location rides along with the foreground one. */
    @Test
    fun `background location is never bundled into a request`() {
        val request = bundleRequest(requiredFor(AutoTrackMode.ANY))
        assertFalse(AppPermission.BACKGROUND_LOCATION.permission in request)
        assertTrue(AppPermission.LOCATION.permission in request)
    }
}
