@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.DEFAULT_PLACE_ICON
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.fixedIcon
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.EmptyState
import de.uhi.enia.ridesafe.ui.components.ListGroupItem
import de.uhi.enia.ridesafe.ui.components.ListGroupItemGap
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/** The singleton shortcut kinds, in display order. */
private val SHORTCUT_KINDS =
    listOf(SavedPlaceKind.HOME, SavedPlaceKind.WORK, SavedPlaceKind.SCHOOL)

@StringRes
internal fun SavedPlaceKind.labelRes(): Int =
    when (this) {
        SavedPlaceKind.HOME -> R.string.place_kind_home
        SavedPlaceKind.WORK -> R.string.place_kind_work
        SavedPlaceKind.SCHOOL -> R.string.place_kind_school
        SavedPlaceKind.GAS_STATION -> R.string.place_kind_gas_station
        SavedPlaceKind.CUSTOM -> R.string.place_kind_custom
    }

/**
 * Saved-addresses management (ADR-03): quick-add chips for missing singleton shortcuts, the list of
 * saved places, and a FAB to add a custom one. Tapping a place opens the editor (where it can be
 * edited or deleted). All rendered with stock M3 components.
 */
@Composable
fun SavedAddressesScreen(
    modifier: Modifier = Modifier,
    addresses: List<SavedAddress>,
    onAdd: (SavedPlaceKind) -> Unit,
    onEdit: (Long) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val existingKinds = addresses.map { it.kind }.toSet()
    val availableShortcuts = SHORTCUT_KINDS.filter { it !in existingKinds }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.saved_addresses_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            MaterialSymbol(
                                symbolName = "arrow_back",
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(SavedPlaceKind.CUSTOM) }) {
                MaterialSymbol(
                    symbolName = "add",
                    contentDescription = stringResource(R.string.saved_addresses_add),
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (availableShortcuts.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableShortcuts.forEach { kind ->
                            AssistChip(
                                onClick = { onAdd(kind) },
                                label = {
                                    Text(stringResource(R.string.saved_address_add_shortcut, stringResource(kind.labelRes())))
                                },
                                leadingIcon = {
                                    MaterialSymbol(
                                        symbolName = kind.fixedIconOrDefault(),
                                        contentDescription = null,
                                        size = 18.dp,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (addresses.isEmpty()) {
                item {
                    EmptyState(
                        symbolName = "location_on",
                        title = stringResource(R.string.saved_addresses_empty_title),
                        message = stringResource(R.string.saved_addresses_empty_message),
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(ListGroupItemGap)) {
                        addresses.forEachIndexed { index, address ->
                            ListGroupItem(index = index, count = addresses.size) {
                                SavedAddressRow(address = address, onClick = { onEdit(address.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedAddressRow(
    address: SavedAddress,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { MaterialSymbol(symbolName = address.icon, contentDescription = null) },
        headlineContent = { Text(address.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            address.address?.let { addr ->
                { Text(shortAddress(addr), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
        trailingContent = {
            MaterialSymbol(
                symbolName = "chevron_right",
                contentDescription = null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** The fixed shortcut icon, or the generic place icon (used only by shortcut chips, which are never custom). */
private fun SavedPlaceKind.fixedIconOrDefault(): String = fixedIcon() ?: DEFAULT_PLACE_ICON
