@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.refuel.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol

@Composable
fun RefuelLoadingScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    RefuelStateScreen(onBack = onBack, showBack = showBack, modifier = modifier) { CircularProgressIndicator() }
}

@Composable
fun RefuelUnavailableScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    RefuelStateScreen(onBack = onBack, showBack = showBack, modifier = modifier) {
        Text(
            stringResource(R.string.refuel_not_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RefuelStateScreen(
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.refuel_edit)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            MaterialSymbol(symbolName = "close", contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
