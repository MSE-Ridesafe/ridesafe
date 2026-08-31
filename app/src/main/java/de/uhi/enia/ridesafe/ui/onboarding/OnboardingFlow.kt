package de.uhi.enia.ridesafe.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.BackNavIcon
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.map.FullScreenMapHost
import de.uhi.enia.ridesafe.ui.components.map.FullScreenMapRequest
import de.uhi.enia.ridesafe.ui.components.map.LocalFullScreenMap

// Matches the app shell's sub-route slide (RidesafeApp.SLIDE_MS) so the wizard moves like the app.
private const val SLIDE_MS = 250

/**
 * First-launch setup wizard (ONB-01): welcome, then one step per setup flow, every one of them
 * skippable (ONB-07). Steps embed the app's real forms rather than re-implementing them —
 * chromeless, so the wizard header is the only bar and back exists exactly once (the header's
 * arrow, mirrored by the system back gesture) — and whatever is created here is exactly what
 * the matching screen would have created. [onFinished] fires on completing, on skipping out,
 * and on the header's close — finishing is the only exit.
 */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    val viewModel: OnboardingViewModel = viewModel()

    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    // The car created by the car step, which the Bluetooth step maps devices onto.
    var vehicleId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Advances only while [from] is still the showing step: a double-tapped skip or a save
    // event outracing recomposition would otherwise advance twice and swallow a step — the
    // same defence the app shell's popOwn() applies to its back stack. Callers pass the step
    // their page was composed for, never the live value.
    fun advanceFrom(from: OnboardingStep) {
        if (step != from) return
        stepAfter(from, hasVehicle = vehicleId != null)?.let { step = it } ?: onFinished()
    }

    // The same guard in the other direction — the header's arrow and the embedded forms'
    // cancel both retreat one step, mirroring what the system back gesture does.
    fun retreatFrom(from: OnboardingStep) {
        if (step != from) return
        stepBefore(from, hasVehicle = vehicleId != null)?.let { step = it }
    }

    // System back walks the wizard, not out of the app, except on the first step. A stale
    // second back is harmless here: before the first step it resolves to null and stops.
    BackHandler(enabled = step != OnboardingStep.WELCOME) {
        stepBefore(step, hasVehicle = vehicleId != null)?.let { step = it }
    }

    // The place step embeds the saved-address form, whose map picker publishes into
    // LocalFullScreenMap — the wizard renders outside RidesafeApp, so it mounts its own host
    // (same reason as there: the map cannot live in a Dialog's translucent window).
    val fullScreenMap = remember { mutableStateOf<FullScreenMapRequest?>(null) }
    CompositionLocalProvider(LocalFullScreenMap provides fullScreenMap) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer) { innerPadding ->
                // Insets are consumed here so the embedded forms' own Scaffolds don't re-apply them.
                Column(
                    Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                        .fillMaxSize(),
                ) {
                    // The welcome page carries its own start/skip choice; the header would double it.
                    if (step != OnboardingStep.WELCOME) {
                        val shown = step
                        WizardHeader(
                            steps = onboardingSteps(hasVehicle = vehicleId != null),
                            current = shown,
                            onBack = { retreatFrom(shown) },
                            onSkip = { advanceFrom(shown) },
                            onClose = onFinished,
                        )
                    }
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = {
                            // Same language as the app shell: forward slides the new step in over a fade,
                            // back fades in what returns while the leaving step slides out right.
                            if (targetState.ordinal >= initialState.ordinal) {
                                slideInHorizontally(tween(SLIDE_MS)) { it } togetherWith fadeOut(tween(SLIDE_MS))
                            } else {
                                fadeIn(tween(SLIDE_MS)) togetherWith slideOutHorizontally(tween(SLIDE_MS)) { it }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "onboardingStep",
                    ) { target ->
                        when (target) {
                            OnboardingStep.WELCOME -> {
                                WelcomePage(
                                    onStart = { advanceFrom(OnboardingStep.WELCOME) },
                                    onSkipAll = onFinished,
                                )
                            }

                            OnboardingStep.CAR -> {
                                CarPage(
                                    viewModel = viewModel,
                                    vehicleId = vehicleId,
                                    onSave = { vehicle, makePrimary ->
                                        viewModel.saveCar(vehicle, makePrimary) { id ->
                                            // Advance only once the row exists — the next step reads it.
                                            vehicleId = id
                                            advanceFrom(OnboardingStep.CAR)
                                        }
                                    },
                                    onBack = { retreatFrom(OnboardingStep.CAR) },
                                )
                            }

                            OnboardingStep.BLUETOOTH -> {
                                BluetoothPage(
                                    viewModel = viewModel,
                                    vehicleId = requireNotNull(vehicleId),
                                    onContinue = { advanceFrom(OnboardingStep.BLUETOOTH) },
                                )
                            }

                            OnboardingStep.AUTO_TRACK -> {
                                AutoTrackPage(onContinue = { advanceFrom(OnboardingStep.AUTO_TRACK) })
                            }

                            OnboardingStep.PLACE -> {
                                PlacePage(
                                    onSaved = { advanceFrom(OnboardingStep.PLACE) },
                                    onBack = { retreatFrom(OnboardingStep.PLACE) },
                                )
                            }

                            OnboardingStep.RECORDING -> {
                                RecordingPage(onContinue = { advanceFrom(OnboardingStep.RECORDING) })
                            }

                            OnboardingStep.SCORES -> {
                                ScoresPage(onFinish = { advanceFrom(OnboardingStep.SCORES) })
                            }
                        }
                    }
                }
            }
            FullScreenMapHost(fullScreenMap)
        }
    }
}

/** Back arrow, step dots, a skip and a leave-the-flow close — the wizard's one row of chrome (ONB-07). */
@Composable
private fun WizardHeader(
    steps: List<OnboardingStep>,
    current: OnboardingStep,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val position = steps.indexOf(current) + 1
    val progressLabel = stringResource(R.string.onboarding_step_progress, position, steps.size)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        BackNavIcon(onBack = onBack)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .semantics { contentDescription = progressLabel },
        ) {
            steps.forEach { s ->
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (s == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                )
            }
        }
        TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip_step)) }
        IconButton(onClick = onClose) {
            MaterialSymbol(
                symbolName = "close",
                contentDescription = stringResource(R.string.onboarding_leave),
            )
        }
    }
}
