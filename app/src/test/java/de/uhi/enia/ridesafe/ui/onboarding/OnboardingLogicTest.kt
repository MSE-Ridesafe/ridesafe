package de.uhi.enia.ridesafe.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingLogicTest {
    @Test
    fun freshInstallShowsOnboarding() {
        assertEquals(FirstRunDecision.SHOW, firstRunDecision(completed = false, vehicleCount = 0))
    }

    @Test
    fun completedFlagSkipsRegardlessOfData() {
        assertEquals(FirstRunDecision.SKIP, firstRunDecision(completed = true, vehicleCount = 0))
        assertEquals(FirstRunDecision.SKIP, firstRunDecision(completed = true, vehicleCount = 3))
    }

    @Test
    fun existingInstallWithVehiclesIsSuppressedAndMarkedDone() {
        // The upgrade path: the flag predates the feature, but the garage proves the user is set up.
        assertEquals(FirstRunDecision.SUPPRESS_AND_MARK_DONE, firstRunDecision(completed = false, vehicleCount = 1))
    }
}
