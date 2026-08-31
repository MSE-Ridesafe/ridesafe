package de.uhi.enia.ridesafe.feature.onboarding

/**
 * The wizard's steps, in order. [onboardingSteps] filters them by state; the welcome step is
 * always first and doubles as the whole flow's skip-out.
 */
enum class OnboardingStep {
    WELCOME,
    CAR,
    BLUETOOTH,
    AUTO_TRACK,
    PLACE,
    RECORDING,
    SCORES,
}

/** The steps that act on the created car, and so drop out when the car step was skipped. */
private val VehicleBoundSteps = setOf(OnboardingStep.BLUETOOTH, OnboardingStep.AUTO_TRACK)

/**
 * The steps for the current state — pure so the sequencing is unit-testable. The Bluetooth and
 * auto-record steps act *on a vehicle* (GAR-08 mapping, TRK-02 detection), so without a created
 * car they have nothing to work with; skipping the car step therefore skips them too.
 */
fun onboardingSteps(hasVehicle: Boolean): List<OnboardingStep> = OnboardingStep.entries.filter { hasVehicle || it !in VehicleBoundSteps }

/** The step after [current], or null when [current] is the last — i.e. advancing finishes. */
fun stepAfter(
    current: OnboardingStep,
    hasVehicle: Boolean,
): OnboardingStep? =
    onboardingSteps(hasVehicle).let { steps ->
        steps.getOrNull(steps.indexOf(current) + 1)
    }

/** The step before [current], or null on the first. */
fun stepBefore(
    current: OnboardingStep,
    hasVehicle: Boolean,
): OnboardingStep? =
    onboardingSteps(hasVehicle).let { steps ->
        steps.indexOf(current).let { if (it > 0) steps[it - 1] else null }
    }
