package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.google.android.gms.maps.model.LatLng
import kotlinx.serialization.Serializable

@Serializable data object RidesRoute : NavKey

@Serializable data class RideDetailRoute(
    val id: Long,
) : NavKey

@Serializable data class MergedRideDetailRoute(
    val groupId: Long,
) : NavKey

@Serializable data object AnalysisQueueRoute : NavKey

/**
 * Rides tab entries: list -> detail. Navigation goes through [onOpen]/[onBack] (the caller
 * mutates the rides back stack and resets the tab-switch flag, so these transitions slide).
 * [viewModel] is one app-scoped instance shared by both screens; its Room
 * [kotlinx.coroutines.flow.Flow] is the source of truth.
 */
fun EntryProviderScope<NavKey>.ridesEntries(
    viewModel: RidesViewModel,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
) {
    entry<RidesRoute> {
        val entries by viewModel.entries.collectAsState()
        val analysis by viewModel.analysisProgress.collectAsState()
        RidesScreen(
            entries = entries,
            analysis = analysis,
            onOpenRide = { onOpen(RideDetailRoute(it)) },
            onOpenMerged = { onOpen(MergedRideDetailRoute(it)) },
            onOpenAnalysisQueue = { onOpen(AnalysisQueueRoute) },
            onMerge = { viewModel.merge(it) },
        )
    }
    entry<AnalysisQueueRoute> {
        val entries by viewModel.entries.collectAsState()
        val analysis by viewModel.analysisProgress.collectAsState()
        // Merged entries carry their stops, so flattening covers every ride a job can point at.
        val rides = remember(entries) { entries.flatMap { it.rides }.associateBy { it.id } }
        AnalysisQueueScreen(progress = analysis, rides = rides, onBack = onBack)
    }
    entry<MergedRideDetailRoute> { key ->
        val stops by viewModel.groupStops(key.groupId).collectAsState(initial = null)
        // Draw one route per stop, disconnected (MRG-07); null until the stops (and their routes) load.
        val segments by produceState<List<List<LatLng>>?>(initialValue = null, stops) {
            value = stops?.takeIf { it.isNotEmpty() }?.let { viewModel.routes(it) }
        }
        val groupEvents by viewModel.groupRideEvents(key.groupId).collectAsState(initial = emptyList())
        MergedRideDetailScreen(
            stops = stops,
            segments = segments,
            rideEvents = groupEvents,
            onBack = onBack,
            onUnmergeAll = { viewModel.unmergeAll(key.groupId) },
            onUnmerge = { viewModel.unmerge(key.groupId, it) },
        )
    }
    entry<RideDetailRoute> { key ->
        val ride by viewModel.ride(key.id).collectAsState(initial = null)
        val addresses by viewModel.savedAddresses.collectAsState()
        val rideEvents by viewModel.rideEvents(key.id).collectAsState(initial = emptyList())
        // Non-null only while this very ride is queued, which is what the detail notice keys off.
        val analysis by viewModel.analysisProgress.collectAsState()
        val analysisProgress = analysis.jobs.firstOrNull { it.rideId == key.id }?.progress
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
            rideEvents = rideEvents,
            startPlace = startPlace,
            endPlace = endPlace,
            analysisProgress = analysisProgress,
            onBack = onBack,
        )
    }
}
