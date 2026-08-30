package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.findExistingSavedPlace
import de.uhi.enia.ridesafe.rides.processing.addressLines
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatShortDistance
import de.uhi.enia.ridesafe.util.haversineMeters

/** The address search field (ADR-05): loading spinner or clear button, IME search records history. */
@Composable
internal fun AddressSearchField(state: AddressSearchState) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun exitSearch() {
        state.setActive(false)
        focusManager.clearFocus()
        keyboard?.hide()
    }
    OutlinedTextField(
        value = state.query,
        onValueChange = state.onQueryChange,
        label = { Text(stringResource(R.string.saved_address_search)) },
        leadingIcon =
            if (state.active) {
                // The M3 search convention: the magnifier turns into the way back out while the
                // suggestion surface is up — the same exit the system back gesture takes.
                {
                    IconButton(onClick = ::exitSearch) {
                        MaterialSymbol(
                            symbolName = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                }
            } else {
                { MaterialSymbol(symbolName = "search", contentDescription = null) }
            },
        trailingIcon = {
            when {
                state.loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                state.query.isNotEmpty() -> {
                    IconButton(onClick = { state.onQueryChange("") }) {
                        MaterialSymbol(
                            symbolName = "close",
                            contentDescription = stringResource(R.string.saved_address_search_clear),
                        )
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions =
            KeyboardActions(
                onSearch = {
                    state.recordQuery()
                    keyboard?.hide()
                },
            ),
        singleLine = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) state.setActive(true) },
    )
}

/**
 * The suggestion surface under the active search field: recents for an empty query, a type-more
 * hint, no-results, or the geocoder's suggestions — each flagged when it duplicates an already
 * saved place (ADR-09) and annotated with its distance from [near].
 */
@Composable
internal fun AddressSearchResults(
    state: AddressSearchState,
    near: LatLng?,
    savedAddresses: List<SavedAddress>,
    editedId: Long?,
    unitSystem: UnitSystemSetting,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (state.query.isBlank()) {
                if (state.recents.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.saved_address_search_recent),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    state.recents.forEach { recent ->
                        ListItem(
                            headlineContent = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { MaterialSymbol(symbolName = "history", contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.clickable { state.onQueryChange(recent) },
                        )
                    }
                }
            } else if (state.query.trim().length < SEARCH_MIN_LENGTH) {
                Text(
                    text = stringResource(R.string.saved_address_search_more_characters),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else if (!state.loading && state.completed && state.results.isEmpty()) {
                Text(
                    text = stringResource(R.string.saved_address_search_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                state.results.forEach { result ->
                    val lines = addressLines(result.address)
                    val matched = findExistingSavedPlace(result.address, result.latitude, result.longitude, savedAddresses, editedId)
                    val distance =
                        near?.let {
                            formatShortDistance(
                                haversineMeters(it.latitude, it.longitude, result.latitude, result.longitude),
                                unitSystem,
                            )
                        }
                    ListItem(
                        headlineContent = { Text(lines.first, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                listOfNotNull(lines.second, distance).takeIf { it.isNotEmpty() }?.let {
                                    Text(it.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                matched?.let {
                                    Text(
                                        text = stringResource(R.string.saved_address_search_already_saved, it.label),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        leadingContent = { MaterialSymbol(symbolName = "location_on", contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.clickable { state.choose(result) },
                    )
                }
            }
        }
    }
}
