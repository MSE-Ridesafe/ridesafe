package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

@Serializable data object GarageRoute : NavKey

@Serializable data class VehicleDetailRoute(
    val id: Long,
) : NavKey

@Serializable data object AddVehicleRoute : NavKey

/**
 * Garage tab entries: list -> detail / add. Navigation is plain back-stack mutation on
 * [backStack] (the garage tab's own stack). [viewModel] is a single app-scoped instance
 * shared by all three screens, so an insert from the add screen propagates to the list
 * via its Room [kotlinx.coroutines.flow.Flow].
 */
fun EntryProviderScope<NavKey>.garageEntries(
    backStack: NavBackStack<NavKey>,
    viewModel: GarageViewModel,
    unitSystem: UnitSystemSetting,
) {
    entry<GarageRoute> {
        val vehicles by viewModel.vehicles.collectAsState(initial = emptyList())
        GarageScreen(
            vehicles = vehicles,
            onVehicleClick = { backStack.add(VehicleDetailRoute(it)) },
            onAddVehicle = { backStack.add(AddVehicleRoute) },
        )
    }
    entry<VehicleDetailRoute> { key ->
        val vehicle by viewModel.vehicle(key.id).collectAsState(initial = null)
        VehicleDetailScreen(
            vehicle = vehicle,
            unitSystem = unitSystem,
            onBack = { backStack.removeLastOrNull() },
        )
    }
    entry<AddVehicleRoute> {
        AddVehicleScreen(
            unitSystem = unitSystem,
            onSave = { vehicle, makePrimary ->
                viewModel.addVehicle(vehicle, makePrimary)
                backStack.removeLastOrNull()
            },
            onBack = { backStack.removeLastOrNull() },
        )
    }
}
