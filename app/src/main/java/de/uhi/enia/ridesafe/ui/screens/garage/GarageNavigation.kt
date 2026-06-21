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

/**
 * Garage tab entries: list -> detail / add. Navigation goes through [onOpen] / [onBack]
 * (the caller mutates the garage back stack and resets the tab-switch flag, so these
 * transitions slide). [viewModel] is a single app-scoped instance shared by all three
 * screens, so an insert from the add screen propagates to the list via its Room
 * [kotlinx.coroutines.flow.Flow].
 */
fun EntryProviderScope<NavKey>.garageEntries(
    viewModel: GarageViewModel,
    unitSystem: UnitSystemSetting,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
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
        )
    }
    entry<AddVehicleRoute> {
        AddVehicleScreen(
            unitSystem = unitSystem,
            onSave = { vehicle, makePrimary ->
                viewModel.addVehicle(vehicle, makePrimary)
                onBack()
            },
            onBack = onBack,
        )
    }
}
