package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

@Serializable data object GarageRoute : NavKey

@Serializable data class VehicleDetailRoute(
    val id: Long,
) : NavKey

@Serializable data object AddVehicleRoute : NavKey

@Serializable data class EditVehicleRoute(
    val id: Long,
) : NavKey

/**
 * Garage tab entries: list -> detail -> edit, plus add. Navigation goes through [onOpen] /
 * [onBack] (the caller mutates the garage back stack and resets the tab-switch flag, so
 * these transitions slide); [onPopToGarage] returns straight to the list after a delete,
 * regardless of how deep the stack is. [viewModel] is a single app-scoped instance shared
 * by all screens, so an insert/edit/delete propagates via its Room
 * [kotlinx.coroutines.flow.Flow].
 */
fun EntryProviderScope<NavKey>.garageEntries(
    viewModel: GarageViewModel,
    unitSystem: UnitSystemSetting,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    onPopToGarage: () -> Unit,
) {
    entry<GarageRoute> {
        val vehicles by viewModel.vehicles.collectAsState(initial = emptyList())
        GarageScreen(
            vehicles = vehicles,
            onVehicleClick = { onOpen(VehicleDetailRoute(it)) },
            onAddVehicle = { onOpen(AddVehicleRoute) },
        )
    }
    entry<VehicleDetailRoute> { key ->
        val vehicle by viewModel.vehicle(key.id).collectAsState(initial = null)
        VehicleDetailScreen(
            vehicle = vehicle,
            unitSystem = unitSystem,
            onBack = onBack,
            onEdit = { onOpen(EditVehicleRoute(key.id)) },
            onDelete = {
                vehicle?.let(viewModel::deleteVehicle)
                onPopToGarage()
            },
            onLinkBluetooth = { address -> vehicle?.let { viewModel.linkBluetooth(it, address) } },
            onUnlinkBluetooth = { address -> vehicle?.let { viewModel.unlinkBluetooth(it, address) } },
        )
    }
    entry<AddVehicleRoute> {
        VehicleFormScreen(
            existing = null,
            unitSystem = unitSystem,
            onSave = { vehicle, makePrimary ->
                viewModel.addVehicle(vehicle, makePrimary)
                onBack()
            },
            onBack = onBack,
        )
    }
    entry<EditVehicleRoute> { key ->
        // Render only once the vehicle has loaded — the form snapshots its initial fields.
        val vehicle by viewModel.vehicle(key.id).collectAsState(initial = null)
        vehicle?.let { loaded ->
            VehicleFormScreen(
                existing = loaded,
                unitSystem = unitSystem,
                onSave = { updated, makePrimary ->
                    viewModel.updateVehicle(updated, makePrimary)
                    onBack()
                },
                onBack = onBack,
                onDelete = {
                    viewModel.deleteVehicle(loaded)
                    onPopToGarage()
                },
            )
        }
    }
}
