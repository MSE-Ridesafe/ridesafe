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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.navigation.RidesafeApp
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme
import kotlinx.coroutines.flow.first

// Matches the app shell's sub-route slide (RidesafeApp.SLIDE_MS) so the wizard moves like the app.
private const val SLIDE_MS = 250

/**
 * The wizard's steps, in order. [onboardingSteps] filters them by state; the welcome step is
 * always first and doubles as the whole flow's skip-out.
 */
enum class OnboardingStep {
    WELCOME,
}

/** The steps for the current state — pure so the sequencing is unit-testable. */
fun onboardingSteps(hasVehicle: Boolean): List<OnboardingStep> = OnboardingStep.entries.toList()

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
            val vehicles = RidesafeDatabase.getInstance(context).vehicleDao().observeAll().first()
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

/**
 * First-launch setup wizard (ONB-01): welcome, then one step per setup flow, every one of them
 * skippable (ONB-07). Steps embed the app's real forms rather than re-implementing them, so
 * whatever is created here is exactly what the matching screen would have created. [onFinished]
 * fires on completing, on skipping out, and on the header's close — finishing is the only exit.
 */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    val hasVehicle = false

    fun advance() {
        stepAfter(step, hasVehicle)?.let { step = it } ?: onFinished()
    }

    // System back walks the wizard, not out of the app, except on the first step.
    BackHandler(enabled = step != OnboardingStep.WELCOME) {
        stepBefore(step, hasVehicle)?.let { step = it }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            // The welcome page carries its own start/skip choice; the header would double it.
            if (step != OnboardingStep.WELCOME) {
                WizardHeader(
                    steps = onboardingSteps(hasVehicle),
                    current = step,
                    onSkip = ::advance,
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
                    OnboardingStep.WELCOME -> WelcomePage(onStart = ::advance, onSkipAll = onFinished)
                }
            }
        }
    }
}

/** Step dots, a skip and a leave-the-flow close — the wizard's one row of chrome (ONB-07). */
@Composable
private fun WizardHeader(
    steps: List<OnboardingStep>,
    current: OnboardingStep,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val position = steps.indexOf(current) + 1
    val progressLabel = stringResource(R.string.onboarding_step_progress, position, steps.size)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f).semantics { contentDescription = progressLabel },
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

/** ONB-01: the pitch — what Ridesafe does, and that all of it stays on the phone (NFR-01). */
@Composable
private fun WelcomePage(
    onStart: () -> Unit,
    onSkipAll: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        // Scrolls under the pinned buttons when the window is short (landscape, split screen).
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            MaterialSymbol(
                symbolName = "directions_car",
                contentDescription = null,
                size = 64.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FeatureRow(
                symbolName = "route",
                title = stringResource(R.string.onboarding_feature_rides_title),
                body = stringResource(R.string.onboarding_feature_rides_body),
            )
            FeatureRow(
                symbolName = "health_and_safety",
                title = stringResource(R.string.onboarding_feature_scores_title),
                body = stringResource(R.string.onboarding_feature_scores_body),
            )
            FeatureRow(
                symbolName = "lock",
                title = stringResource(R.string.onboarding_feature_privacy_title),
                body = stringResource(R.string.onboarding_feature_privacy_body),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_welcome_start))
        }
        TextButton(onClick = onSkipAll, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.onboarding_welcome_skip))
        }
    }
}

@Composable
private fun FeatureRow(
    symbolName: String,
    title: String,
    body: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        MaterialSymbol(
            symbolName = symbolName,
            contentDescription = null,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun WelcomePreview() {
    RidesafeTheme {
        OnboardingFlow(onFinished = {})
    }
}
