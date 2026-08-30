package de.uhi.enia.ridesafe.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R

/**
 * Scaffold shared by the detail screens: a transparent top bar (the NavigationSuiteScaffold's
 * surfaceContainer shows through — detail screens are transparent, forms opaque; see the
 * restructure plan, R9) over a 16.dp scrolling card column. [showBack] hides the arrow when the
 * screen is pinned beside its list in the two-pane layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = title,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = { BackNavIcon(onBack = onBack, showBack = showBack) },
                actions = actions,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** The guarded back arrow every top bar shares; [showBack] hides it in the pinned two-pane detail. */
@Composable
fun BackNavIcon(
    onBack: () -> Unit,
    showBack: Boolean = true,
    enabled: Boolean = true,
) {
    if (showBack) {
        IconButton(onClick = onBack, enabled = enabled) {
            MaterialSymbol(
                symbolName = "arrow_back",
                contentDescription = stringResource(R.string.action_back),
            )
        }
    }
}
