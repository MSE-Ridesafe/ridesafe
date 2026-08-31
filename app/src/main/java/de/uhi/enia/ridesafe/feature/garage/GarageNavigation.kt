package de.uhi.enia.ridesafe.feature.garage

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.DetailPlaceholder
import de.uhi.enia.ridesafe.core.components.ListPaneFocusSink
import de.uhi.enia.ridesafe.feature.garage.ui.GarageScreen
import de.uhi.enia.ridesafe.feature.garage.ui.VehicleDetailScreen
import de.uhi.enia.ridesafe.feature.garage.ui.VehicleFormScreen
import kotlinx.serialization.Serializable

@Serializable data object GarageRoute : NavKey

@Serializable data class VehicleDetailRoute(
    val id: Long,
) : NavKey

@Serializable data object AddVehicleRoute : NavKey

@Serializable data class EditVehicleRoute(
    val id: Long,
) : NavKey

/** Ties this tab's list and detail routes into one scene, distinct from the other tabs'. */
private const val GARAGE_SCENE = "garage"

/**
 * Garage tab entries: list -> detail -> edit, plus add. Navigation goes through [onOpen] /
 * [onBack] (the caller mutates the garage back stack and resets the tab-switch flag, so
 * these transitions slide). [onBack] carries the closing screen's own key, so a back event that
 * outruns recomposition cannot pop the screen underneath it; [onPopToGarage] returns straight
 * to the list after a delete,
 * regardless of how deep the stack is. [viewModel] is a single app-scoped instance shared
 * by all screens, so an insert/edit/delete propagates via its Room
 * [kotlinx.coroutines.flow.Flow].
 *
 * The pane metadata groups the list and every screen below it into one list-detail scene, so on a
 * wide window they sit side by side instead of stacking. [selectedId] is the open vehicle, which
 * the list highlights; [showBack] is false once both panes are visible, so the vehicle detail drops
 * the arrow back to a list that is already on screen. [VehicleFormScreen] is not passed it: the form
 * opens a level deeper and its cancel discards edits, which stays reachable in every layout.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.garageEntries(
    viewModel: GarageViewModel,
    selectedId: Long?,
    showBack: Boolean,
    onOpen: (NavKey) -> Unit,
    onBack: (NavKey) -> Unit,
    onPopToGarage: () -> Unit,
) {
    entry<GarageRoute>(
        metadata =
            ListDetailSceneStrategy.listPane(sceneKey = GARAGE_SCENE) {
                DetailPlaceholder(stringResource(R.string.placeholder_select_vehicle))
            },
    ) {
        val vehicles by viewModel.vehicles.collectAsState()
        ListPaneFocusSink {
            GarageScreen(
                vehicles = vehicles,
                onVehicleClick = { onOpen(VehicleDetailRoute(it)) },
                onAddVehicle = { onOpen(AddVehicleRoute) },
                selectedId = selectedId,
            )
        }
    }
    entry<VehicleDetailRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = GARAGE_SCENE)) { key ->
        val vehicle by viewModel.vehicle(key.id).collectAsState(initial = null)
        VehicleDetailScreen(
            vehicle = vehicle,
            onBack = { onBack(key) },
            onEdit = { onOpen(EditVehicleRoute(key.id)) },
            onDelete = {
                vehicle?.let(viewModel::deleteVehicle)
                onPopToGarage()
            },
            showBack = showBack,
            onChooseImage = { uri -> vehicle?.let { viewModel.setVehicleImage(it, uri) } },
            onRemoveImage = { vehicle?.let { viewModel.removeVehicleImage(it) } },
            onLinkBluetooth = { device -> vehicle?.let { viewModel.linkBluetooth(it, device) } },
        ) { address -> vehicle?.let { viewModel.unlinkBluetooth(it, address) } }
    }
    entry<AddVehicleRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = GARAGE_SCENE)) {
        // Latch against a double-tapped save inserting the vehicle twice.
        var saved by remember { mutableStateOf(false) }
        VehicleFormScreen(
            existing = null,
            onSave = { vehicle, makePrimary ->
                if (!saved) {
                    saved = true
                    viewModel.addVehicle(vehicle, makePrimary)
                    onBack(AddVehicleRoute)
                }
            },
            onBack = { onBack(AddVehicleRoute) },
        )
    }
    entry<EditVehicleRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = GARAGE_SCENE)) { key ->
        // Render only once the vehicle has loaded — the form snapshots its initial fields.
        val vehicle by viewModel.vehicle(key.id).collectAsState(initial = null)
        vehicle?.let { loaded ->
            VehicleFormScreen(
                existing = loaded,
                onSave = { updated, makePrimary ->
                    viewModel.updateVehicle(updated, makePrimary)
                    onBack(key)
                },
                onBack = { onBack(key) },
                onDelete = {
                    viewModel.deleteVehicle(loaded)
                    onPopToGarage()
                },
            )
        }
    }
}
