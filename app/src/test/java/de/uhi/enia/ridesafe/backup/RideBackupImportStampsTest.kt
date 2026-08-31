package de.uhi.enia.ridesafe.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which analysis stamps an archive may hand over, and which have to be earned again here. */
class RideBackupImportStampsTest {
    @Test
    fun stagesWhoseOutputLivesOnTheRideRowAreNotAdopted() {
        // The archive carries no dynamics profile, score or eco profile, so adopting these would
        // leave the imported ride stamped "analysed" and permanently unscored.
        listOf("events", "score", "eco").forEach {
            assertFalse(it, adoptableAnalysisState(it, routeCurrent = true, routeFileIncluded = true))
        }
    }

    @Test
    fun routeIsAdoptedOnlyWithItsSidecarAndStagesWithExportedOutputAlways() {
        assertTrue(adoptableAnalysisState("route", routeCurrent = true, routeFileIncluded = true))
        assertFalse(adoptableAnalysisState("route", routeCurrent = false, routeFileIncluded = true))
        assertFalse(adoptableAnalysisState("route", routeCurrent = true, routeFileIncluded = false))
        // Corrected endpoints ride along on the ride row; the axis persists nothing either way.
        assertTrue(adoptableAnalysisState("endpoints", routeCurrent = true, routeFileIncluded = true))
        assertTrue(adoptableAnalysisState("axis", routeCurrent = true, routeFileIncluded = true))
    }
}
