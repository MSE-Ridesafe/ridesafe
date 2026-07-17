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

@Serializable data class MergedRideDetailRoute(
    val groupId: Long,
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
        val entries by viewModel.entries.collectAsState()
        RidesScreen(
            entries = entries,
            unitSystem = unitSystem,
            onOpenRide = { onOpen(RideDetailRoute(it)) },
            onOpenMerged = { onOpen(MergedRideDetailRoute(it)) },
            onMerge = { viewModel.merge(it) },
        )
    }
    entry<MergedRideDetailRoute> { key ->
        val stops by viewModel.groupStops(key.groupId).collectAsState(initial = null)
        // Draw one route per stop, disconnected (MRG-07); null until the stops (and their routes) load.
        val segments by produceState<List<List<LatLng>>?>(initialValue = null, stops) {
            value = stops?.takeIf { it.isNotEmpty() }?.let { viewModel.routes(it) }
        }
        MergedRideDetailScreen(
            stops = stops,
            segments = segments,
            unitSystem = unitSystem,
            onBack = onBack,
            onUnmergeAll = { viewModel.unmergeAll(key.groupId) },
            onUnmerge = { viewModel.unmerge(key.groupId, it) },
        )
    }
    entry<RideDetailRoute> { key ->
        val ride by viewModel.ride(key.id).collectAsState(initial = null)
        val addresses by viewModel.savedAddresses.collectAsState()
        // Load the route once the ride row has loaded; null until then = "loading".
        val route by produceState<List<LatLng>?>(initialValue = null, ride) {
            value = ride?.let { viewModel.route(it) }
        }
        // Resolve the stored matched-address ids (ADR-07) to the places, for the detail labels (ADR-09).
        val startPlace = ride?.startAddressId?.let { id -> addresses.firstOrNull { it.id == id } }
        val endPlace = ride?.endAddressId?.let { id -> addresses.firstOrNull { it.id == id } }
        RideDetailScreen(
            ride = ride,
            route = route,
            startPlace = startPlace,
            endPlace = endPlace,
            unitSystem = unitSystem,
            onBack = onBack,
        )
    }
}
