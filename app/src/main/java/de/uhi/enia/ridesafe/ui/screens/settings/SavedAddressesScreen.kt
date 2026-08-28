@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.DEFAULT_PLACE_ICON
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.fixedIcon
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/** The three shortcut kinds, in display order. */
private val SHORTCUT_KINDS = listOf(SavedPlaceKind.HOME, SavedPlaceKind.WORK, SavedPlaceKind.SCHOOL)

@StringRes
internal fun SavedPlaceKind.labelRes(): Int =
    when (this) {
        SavedPlaceKind.HOME -> R.string.place_kind_home
        SavedPlaceKind.WORK -> R.string.place_kind_work
        SavedPlaceKind.SCHOOL -> R.string.place_kind_school
        SavedPlaceKind.CUSTOM -> R.string.place_kind_custom
    }

/**
 * Saved-addresses management (ADR-03): quick-add chips for the shortcut kinds not created yet, the
 * list of saved places, and a FAB to add a custom one. Tapping a place opens the editor (where it can
 * be edited or deleted). All rendered with stock M3 components.
 */
@Composable
fun SavedAddressesScreen(
    addresses: List<SavedAddress>,
    onAdd: (SavedPlaceKind) -> Unit,
    onEdit: (Long) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val existingKinds = addresses.map { it.kind }.toSet()
    val missingShortcuts = SHORTCUT_KINDS.filterNot { it in existingKinds }

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
            if (missingShortcuts.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        missingShortcuts.forEach { kind ->
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
                item { EmptySavedAddresses() }
            } else {
                item {
                    Card(
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            addresses.forEachIndexed { index, address ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                                }
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

@Composable
private fun EmptySavedAddresses() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MaterialSymbol(
            symbolName = "location_on",
            contentDescription = null,
            size = 64.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.saved_addresses_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.saved_addresses_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The fixed shortcut icon, or the generic place icon (used only by shortcut chips, which are never custom). */
private fun SavedPlaceKind.fixedIconOrDefault(): String = fixedIcon() ?: DEFAULT_PLACE_ICON
