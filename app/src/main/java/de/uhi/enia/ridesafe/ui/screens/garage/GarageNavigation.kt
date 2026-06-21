package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

@Serializable data object GarageGraph

@Serializable data object GarageRoute

@Serializable data class VehicleDetailRoute(
    val id: Long,
)

@Serializable data object AddVehicleRoute

/**
 * Garage tab graph: list -> detail / add. The [GarageViewModel] is scoped to the
 * [GarageGraph] back-stack entry so all three screens share one instance (and one
 * Room Flow), keeping the list in sync after an insert.
 */
fun NavGraphBuilder.garageGraph(
    navController: NavController,
    unitSystem: UnitSystemSetting,
) {
    navigation<GarageGraph>(startDestination = GarageRoute) {
        composable<GarageRoute> { entry ->
            val viewModel = entry.garageViewModel(navController)
            val vehicles by viewModel.vehicles.collectAsState(initial = emptyList())
            GarageScreen(
                vehicles = vehicles,
                onVehicleClick = { navController.navigate(VehicleDetailRoute(it)) },
                onAddVehicle = { navController.navigate(AddVehicleRoute) },
            )
        }
        composable<VehicleDetailRoute> { entry ->
            val viewModel = entry.garageViewModel(navController)
            val id = entry.toRoute<VehicleDetailRoute>().id
            val vehicle by viewModel.vehicle(id).collectAsState(initial = null)
            VehicleDetailScreen(
                vehicle = vehicle,
                unitSystem = unitSystem,
                onBack = { navController.popBackStack() },
            )
        }
        composable<AddVehicleRoute> { entry ->
            val viewModel = entry.garageViewModel(navController)
            AddVehicleScreen(
                unitSystem = unitSystem,
                onSave = { vehicle, makePrimary ->
                    viewModel.addVehicle(vehicle, makePrimary)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Graph-scoped [GarageViewModel] shared by every screen in the garage tab. */
@Composable
private fun NavBackStackEntry.garageViewModel(navController: NavController): GarageViewModel {
    val parentEntry = remember(this) { navController.getBackStackEntry(GarageGraph) }
    return viewModel(parentEntry)
}
