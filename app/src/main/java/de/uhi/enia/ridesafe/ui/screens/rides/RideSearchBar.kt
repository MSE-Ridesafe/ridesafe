@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/** OutlinedTextField's own default height — the filter button matches it so the row lines up. */
private val SEARCH_FIELD_HEIGHT = 56.dp

/**
 * The Logbook's search field (LOG-11) with the filter button beside it, badged with how many
 * filters are narrowing the list. Sits above the list rather than hiding behind a search icon, so
 * both affordances are visible without a tap.
 */
@Composable
fun RideSearchBar(
    query: String,
    activeFilterCount: Int,
    onQueryChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            placeholder = { Text(stringResource(R.string.rides_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            leadingIcon =
                if (focused) {
                    // The M3 search convention: the magnifier turns into the way back out of the
                    // focused search. The query (and the filtered list) stays; only focus leaves.
                    {
                        IconButton(onClick = { focusManager.clearFocus() }) {
                            MaterialSymbol(
                                symbolName = "arrow_back",
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                } else {
                    { MaterialSymbol(symbolName = "search", contentDescription = null) }
                },
            trailingIcon =
                if (query.isEmpty()) {
                    null
                } else {
                    {
                        // A bare IconButton: the field is already the container, so a filled one
                        // would sit inside it as a second surface.
                        IconButton(onClick = { onQueryChange("") }) {
                            MaterialSymbol(
                                symbolName = "close",
                                contentDescription = stringResource(R.string.rides_search_clear),
                            )
                        }
                    }
                },
        )
        BadgedBox(
            badge = { if (activeFilterCount > 0) Badge { Text(activeFilterCount.toString()) } },
        ) {
            // Sized to the text field's own height so the two read as one control, rather than a
            // button parked next to a bar.
            FilledTonalIconButton(onClick = onOpenFilters, modifier = Modifier.size(SEARCH_FIELD_HEIGHT)) {
                MaterialSymbol(
                    symbolName = "tune",
                    contentDescription = stringResource(R.string.rides_filter_open),
                )
            }
        }
    }
}
