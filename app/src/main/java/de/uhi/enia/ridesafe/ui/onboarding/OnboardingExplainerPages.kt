package de.uhi.enia.ridesafe.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.SafetyScoreCard
import de.uhi.enia.ridesafe.ui.components.EcoSection
import kotlinx.coroutines.flow.first

/** ONB-01: the pitch — what Ridesafe does, and that all of it stays on the phone (NFR-01). */
@Composable
internal fun WelcomePage(
    onStart: () -> Unit,
    onSkipAll: () -> Unit,
) {
    StepPage(
        primaryLabel = stringResource(R.string.onboarding_welcome_start),
        onPrimary = onStart,
        secondary = {
            TextButton(onClick = onSkipAll, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.onboarding_welcome_skip))
            }
        },
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
}

/**
 * ONB-06: how a ride actually gets recorded — the automatic path just configured, and the manual
 * fallback (TRK-07) for a car without a linked device or a trigger that missed.
 */
@Composable
internal fun RecordingPage(onContinue: () -> Unit) {
    StepPage(primaryLabel = stringResource(R.string.onboarding_continue), onPrimary = onContinue) {
        StepIntro(
            symbolName = "radio_button_checked",
            title = stringResource(R.string.onboarding_recording_title),
            body = stringResource(R.string.onboarding_recording_body),
        )
        FeatureRow(
            symbolName = "bluetooth",
            title = stringResource(R.string.onboarding_recording_auto_title),
            body = stringResource(R.string.onboarding_recording_auto_body),
        )
        FeatureRow(
            symbolName = "play_arrow",
            title = stringResource(R.string.onboarding_recording_manual_title),
            body =
                stringResource(
                    R.string.onboarding_recording_manual_body,
                    stringResource(R.string.home_record_start),
                ),
        )
        FeatureRow(
            symbolName = "stop_circle",
            title = stringResource(R.string.onboarding_recording_stop_title),
            body = stringResource(R.string.onboarding_recording_stop_body),
        )
    }
}

/**
 * ONB-06: what the safety score (ANL-01/DSH-06) and eco level (ANL-03) mean, shown on the very
 * cards the app uses — with sample values, clearly labelled as such, because a brand-new user has
 * nothing scored yet and the dashboard hides both cards until a first ride is analyzed. Last
 * step, so its primary action finishes the flow.
 */
@Composable
internal fun ScoresPage(onFinish: () -> Unit) {
    StepPage(primaryLabel = stringResource(R.string.onboarding_done_cta), onPrimary = onFinish) {
        StepIntro(
            symbolName = "health_and_safety",
            title = stringResource(R.string.onboarding_scores_title),
            body = stringResource(R.string.onboarding_scores_body),
        )
        Text(
            text = stringResource(R.string.onboarding_scores_sample_note),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        SafetyScoreCard(score = SampleScore)
        EcoSection(level = 2)
    }
}

/** Plausible mid-90s-driver values for the explainer — the penalties are display-irrelevant. */
private val SampleScore =
    SafetyScore(
        total = 87,
        braking = 84,
        acceleration = 91,
        cornering = 88,
        brakingPenalty = 0.0,
        accelerationPenalty = 0.0,
        corneringPenalty = 0.0,
        qualifiedSeconds = 0.0,
    )