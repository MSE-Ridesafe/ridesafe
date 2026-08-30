package de.uhi.enia.ridesafe.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/**
 * Shared page frame: scrolling body, pinned primary action, optional secondary action under it.
 * Pinning matters — the action must stay reachable when the body outgrows a short window
 * (landscape, split screen), where a scrolled-away button reads as a dead end.
 */
@Composable
internal fun StepPage(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondary: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
            Text(primaryLabel)
        }
        secondary?.invoke(this)
    }
}

/** Title + body atop a form step — the stand-in for the app bar the embedded form dropped. */
@Composable
internal fun StepFormHeader(
    title: String,
    body: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The icon + title + body block that opens every explainer-style step page. */
@Composable
internal fun StepIntro(
    symbolName: String,
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MaterialSymbol(
            symbolName = symbolName,
            contentDescription = null,
            size = 48.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun FeatureRow(
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
