package de.uhi.enia.ridesafe.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import de.uhi.enia.ridesafe.data.db.RidesafeDatabase
import de.uhi.enia.ridesafe.feature.onboarding.FirstRunDecision
import de.uhi.enia.ridesafe.feature.onboarding.OnboardingPrefs
import de.uhi.enia.ridesafe.feature.onboarding.firstRunDecision
import de.uhi.enia.ridesafe.feature.onboarding.ui.OnboardingFlow
import kotlinx.coroutines.flow.first

/**
 * Decides between the onboarding wizard and the app proper (ONB-01) — the composable MainActivity
 * hosts. A Settings replay request wins outright; a completed flag skips straight to the app;
 * otherwise the first run is resolved once from the garage ([firstRunDecision]): an install that
 * already has vehicles predates this feature and is marked done silently.
 */
@Composable
fun OnboardingGate() {
    val context = LocalContext.current
    if (OnboardingPrefs.replayRequested) {
        OnboardingFlow(onFinished = { OnboardingPrefs.setCompleted(context) })
        return
    }
    if (OnboardingPrefs.isCompleted(context)) {
        RidesafeApp()
        return
    }
    val decision by
        produceState<FirstRunDecision?>(initialValue = null) {
            val vehicles =
                RidesafeDatabase
                    .getInstance(context)
                    .vehicleDao()
                    .observeAll()
                    .first()
            value =
                firstRunDecision(completed = false, vehicleCount = vehicles.size).also {
                    if (it == FirstRunDecision.SUPPRESS_AND_MARK_DONE) OnboardingPrefs.setCompleted(context)
                }
        }
    when (decision) {
        // Plain themed ground for the frames the Room read takes on a cold start.
        null -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {}

        FirstRunDecision.SHOW -> OnboardingFlow(onFinished = { OnboardingPrefs.setCompleted(context) })

        else -> RidesafeApp()
    }
}
