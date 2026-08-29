package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import kotlinx.serialization.Serializable

@Serializable data object SavedAddressesRoute : NavKey

/** Add a place; [kind] preselects a shortcut (Home/Work/School) or CUSTOM for a free-form place. */
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
        SavedAddressFormScreen(
            existing = null,
            presetKind = SavedPlaceKind.valueOf(key.kind),
            onSave = {
                viewModel.add(it)
                onBack(key)
            },
            onBack = { onBack(key) },
        )
    }
    entry<EditSavedAddressRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) { key ->
        // Render only once the address has loaded — the form snapshots its initial fields.
        val address by viewModel.address(key.id).collectAsState(initial = null)
        address?.let { loaded ->
            SavedAddressFormScreen(
                existing = loaded,
                presetKind = loaded.kind,
                onSave = {
                    viewModel.update(it)
                    onBack(key)
                },
                onBack = { onBack(key) },
                onDelete = {
                    viewModel.delete(loaded)
                    onBack(key)
                },
            )
        }
    }
}
