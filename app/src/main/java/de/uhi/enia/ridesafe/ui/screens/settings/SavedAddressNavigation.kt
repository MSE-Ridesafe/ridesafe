package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import kotlinx.serialization.Serializable

@Serializable data object SavedAddressesRoute : NavKey

/** Add a place; [kind] preselects a fixed shortcut or CUSTOM for a free-form place. */
@Serializable data class AddSavedAddressRoute(
    val kind: String = SavedPlaceKind.CUSTOM.name,
) : NavKey

@Serializable data class EditSavedAddressRoute(
    val id: Long,
) : NavKey

/**
 * Saved-addresses entries: list -> add/edit editor. Registered from [settingsEntries] so they live in
 * the Settings back stack. [viewModel] is one app-scoped instance shared by both screens; its Room
 * [kotlinx.coroutines.flow.Flow] is the source of truth, and every mutation re-matches rides (ADR-07).
 */
fun EntryProviderScope<NavKey>.savedAddressEntries(
    viewModel: SavedAddressViewModel,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
) {
    entry<SavedAddressesRoute> {
        val addresses by viewModel.addresses.collectAsState()
        SavedAddressesScreen(
            addresses = addresses,
            onAdd = { kind -> onOpen(AddSavedAddressRoute(kind.name)) },
            onEdit = { id -> onOpen(EditSavedAddressRoute(id)) },
            onBack = onBack,
        )
    }
    entry<AddSavedAddressRoute> { key ->
        val addresses by viewModel.addresses.collectAsState()
        SavedAddressFormScreen(
            existing = null,
            presetKind = SavedPlaceKind.valueOf(key.kind),
            savedAddresses = addresses,
            onSave = {
                viewModel.add(it)
                onBack()
            },
            onBack = onBack,
        )
    }
    entry<EditSavedAddressRoute> { key ->
        // Render only once the address has loaded — the form snapshots its initial fields.
        val address by viewModel.address(key.id).collectAsState(initial = null)
        val addresses by viewModel.addresses.collectAsState()
        address?.let { loaded ->
            SavedAddressFormScreen(
                existing = loaded,
                presetKind = loaded.kind,
                savedAddresses = addresses,
                onSave = {
                    viewModel.update(it)
                    onBack()
                },
                onBack = onBack,
                onDelete = {
                    viewModel.delete(loaded)
                    onBack()
                },
            )
        }
    }
}
