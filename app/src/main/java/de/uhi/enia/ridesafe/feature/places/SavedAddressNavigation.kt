package de.uhi.enia.ridesafe.feature.places

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.data.entity.SavedPlaceKind
import de.uhi.enia.ridesafe.feature.SETTINGS_SCENE
import de.uhi.enia.ridesafe.feature.places.ui.SavedAddressFormScreen
import de.uhi.enia.ridesafe.feature.places.ui.SavedAddressesScreen
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
 * Saved-addresses entries: list -> add/edit editor. Registered from the Settings entries so they live in
 * the Settings back stack. [viewModel] is one app-scoped instance shared by both screens; its Room
 * [kotlinx.coroutines.flow.Flow] is the source of truth, and every mutation re-matches rides (ADR-07).
 * All three sit in the Settings tab's detail pane, so on a wide window the settings menu stays
 * visible beside them and [showBack] drops the list's back arrow. The editor keeps its cancel in
 * every layout — it opens a level deeper than the menu can reach, and that X discards edits.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.savedAddressEntries(
    viewModel: SavedAddressViewModel,
    showBack: Boolean,
    onOpen: (NavKey) -> Unit,
    onBack: (NavKey) -> Unit,
) {
    entry<SavedAddressesRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) {
        val addresses by viewModel.addresses.collectAsState()
        SavedAddressesScreen(
            addresses = addresses,
            onAdd = { kind -> onOpen(AddSavedAddressRoute(kind.name)) },
            onEdit = { id -> onOpen(EditSavedAddressRoute(id)) },
            onBack = { onBack(SavedAddressesRoute) },
            showBack = showBack,
        )
    }
    entry<AddSavedAddressRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) { key ->
        val addresses by viewModel.addresses.collectAsState()
        // Latch against a double-tapped save inserting the place twice.
        var saved by remember { mutableStateOf(false) }
        SavedAddressFormScreen(
            existing = null,
            presetKind = SavedPlaceKind.valueOf(key.kind),
            savedAddresses = addresses,
            onSave = {
                if (!saved) {
                    saved = true
                    viewModel.add(it)
                    onBack(key)
                }
            },
            onBack = { onBack(key) },
        )
    }
    entry<EditSavedAddressRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) { key ->
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
                    onBack(key)
                },
                onBack = { onBack(key) },
                // Named, not trailing: a trailing lambda would bind to whatever parameter is
                // last (today the onboarding's `embedded` flag), not to onDelete.
                onDelete = {
                    viewModel.delete(loaded)
                    onBack(key)
                },
            )
        }
    }
}
