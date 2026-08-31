package de.uhi.enia.ridesafe.feature.onboarding

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

    @Test
    fun withAVehicleEveryStepRunsInDeclarationOrder() {
        assertEquals(OnboardingStep.entries.toList(), onboardingSteps(hasVehicle = true))
    }

    @Test
    fun withoutAVehicleTheVehicleBoundStepsDropOut() {
        assertEquals(
            listOf(
                OnboardingStep.WELCOME,
                OnboardingStep.CAR,
                OnboardingStep.PLACE,
                OnboardingStep.RECORDING,
                OnboardingStep.SCORES,
            ),
            onboardingSteps(hasVehicle = false),
        )
    }

    @Test
    fun advancingFromTheCarStepDependsOnWhetherACarWasCreated() {
        assertEquals(OnboardingStep.BLUETOOTH, stepAfter(OnboardingStep.CAR, hasVehicle = true))
        assertEquals(OnboardingStep.PLACE, stepAfter(OnboardingStep.CAR, hasVehicle = false))
    }

    @Test
    fun advancingPastTheLastStepFinishes() {
        assertEquals(null, stepAfter(OnboardingStep.SCORES, hasVehicle = true))
        assertEquals(null, stepAfter(OnboardingStep.SCORES, hasVehicle = false))
    }

    @Test
    fun backStopsAtTheWelcomeStep() {
        assertEquals(null, stepBefore(OnboardingStep.WELCOME, hasVehicle = true))
        assertEquals(OnboardingStep.CAR, stepBefore(OnboardingStep.PLACE, hasVehicle = false))
        assertEquals(OnboardingStep.AUTO_TRACK, stepBefore(OnboardingStep.PLACE, hasVehicle = true))
    }
}
