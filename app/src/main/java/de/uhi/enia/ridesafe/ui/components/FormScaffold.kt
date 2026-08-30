package de.uhi.enia.ridesafe.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R

/**
 * Scaffold shared by the add/edit forms: an opaque top bar with a close (X) navigation icon and a
 * pinned save action — or, [embedded] inside another flow like onboarding, no chrome at all and a
 * full-width save button pinned below the fields. Forms keep the opaque top bar deliberately;
 * detail screens use the transparent one (see the restructure plan, R9).
 *
 * Embedded, the whole form lifts over the keyboard: the pinned save stays reachable and the
 * field viewport shrinks exactly once — imePadding() consumes the inset, so the content's own
 * imePadding (still needed without a bottom bar) measures zero inside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScaffold(
    title: String,
    canSave: Boolean,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    backEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = if (embedded) modifier.imePadding() else modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = { Text(title) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = backEnabled) {
                            MaterialSymbol(symbolName = "close", contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        Button(modifier = Modifier.padding(end = 8.dp), onClick = onSave, enabled = canSave) {
                            Text(stringResource(R.string.action_save))
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (embedded) {
                Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
