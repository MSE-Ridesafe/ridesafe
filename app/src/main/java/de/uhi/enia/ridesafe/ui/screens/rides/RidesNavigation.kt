package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

@Serializable data object RidesRoute : NavKey

@Serializable data class RideDetailRoute(
    val id: Long,
) : NavKey

/**
 * Rides tab entries: list -> detail. Navigation goes through [onOpen]/[onBack] (the caller
 * mutates the rides back stack and resets the tab-switch flag, so these transitions slide).
 * [viewModel] is one app-scoped instance shared by both screens; its Room
 * [kotlinx.coroutines.flow.Flow] is the source of truth.
 */
fun EntryProviderScope<NavKey>.ridesEntries(
    viewModel: RidesViewModel,
    unitSystem: UnitSystemSetting,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
) {
    entry<RidesRoute> {
        val rides by viewModel.rides.collectAsState(initial = emptyList())
        RidesScreen(
            rides = rides,
            onRideClick = { onOpen(RideDetailRoute(it)) },
        )
    }
    entry<RideDetailRoute> { key ->
        val ride by viewModel.ride(key.id).collectAsState(initial = null)
        // Load the route once the ride row has loaded; null until then = "loading".
        val route by produceState<List<LatLng>?>(initialValue = null, ride) {
            value = ride?.let { viewModel.route(it) }
        }
        RideDetailScreen(
            ride = ride,
            route = route,
            unitSystem = unitSystem,
            onBack = onBack,
        )
    }
}
