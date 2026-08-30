package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.DetailPlaceholder
import de.uhi.enia.ridesafe.ui.components.ListPaneFocusSink
import kotlinx.serialization.Serializable

@Serializable data object RidesRoute : NavKey

@Serializable data class RideDetailRoute(
    val id: Long,
) : NavKey

@Serializable data class MergedRideDetailRoute(
    val groupId: Long,
) : NavKey

@Serializable data object AnalysisQueueRoute : NavKey

@Serializable data object AddRefuelRoute : NavKey

@Serializable data class EditRefuelRoute(
    val id: Long,
) : NavKey

/** Ties this tab's list and detail routes into one scene, distinct from the other tabs'. */
private const val RIDES_SCENE = "rides"

/**
 * Rides tab entries: list -> detail. Navigation goes through [onOpen]/[onBack] (the caller
 * mutates the rides back stack and resets the tab-switch flag, so these transitions slide);
 * [onBack] carries the closing screen's own key, so a back event that outruns recomposition
 * cannot pop the screen underneath it.
 * [viewModel] is one app-scoped instance shared by both screens; its Room
 * [kotlinx.coroutines.flow.Flow] is the source of truth.
 *
 * The pane metadata groups the list and every screen below it into one list-detail scene, so on a
 * wide window they sit side by side instead of stacking. [selectedKey] is the open ride's
 * [LogbookEntry.key], which the list highlights; [showBack] is false once both panes are visible,
 * where a back arrow on a pinned pane would be meaningless.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.ridesEntries(
    viewModel: RidesViewModel,
    selectedKey: String?,
    showBack: Boolean,
    selectionDismissRequests: State<Int>,
    onOpen: (NavKey) -> Unit,
    onBack: (NavKey) -> Unit,
) {
    entry<RidesRoute>(
        metadata =
            ListDetailSceneStrategy.listPane(sceneKey = RIDES_SCENE) {
                DetailPlaceholder(stringResource(R.string.placeholder_select_ride))
            },
    ) {
        val timeline by viewModel.timeline.collectAsState()
        val analysis by viewModel.analysisProgress.collectAsState()

        val exportState by viewModel.exportState.collectAsState()
        val logbookOperationState by viewModel.logbookOperationState.collectAsState()

        // The garage and the saved places feed the filter sheet's dropdowns (LOG-07, LOG-12).
        val vehicles by viewModel.vehicles.collectAsState()
        val places by viewModel.savedAddresses.collectAsState()
        val ridesWithEvents by viewModel.ridesWithEvents.collectAsState()
        val filter by viewModel.filter.collectAsState()
        ListPaneFocusSink {
            RidesScreen(
                timeline = timeline,
                analysis = analysis,
                exportState = exportState,
                vehicles = vehicles,
                places = places,
                ridesWithEvents = ridesWithEvents,
                filter = filter,
                onFilterChange = viewModel::setFilter,
                onOpenRide = { onOpen(RideDetailRoute(it)) },
                onOpenMerged = { onOpen(MergedRideDetailRoute(it)) },
                onOpenRefuel = { onOpen(EditRefuelRoute(it)) },
                onOpenAnalysisQueue = { onOpen(AnalysisQueueRoute) },
                onMerge = viewModel::merge,
                onUnmerge = viewModel::unmergeAll,
                onAttach = viewModel::attachRefuels,
                onDetach = viewModel::detachRefuels,
                onDelete = viewModel::deleteEntries,
                logbookOperationState = logbookOperationState,
                onLogbookOperationResultConsumed = viewModel::consumeLogbookOperationResult,
                onExport = viewModel::export,
                onExportResultConsumed = viewModel::consumeExportResult,
                onAddRefuel = { onOpen(AddRefuelRoute) },
                selectionDismissRequests = selectionDismissRequests,
                selectedKey = selectedKey,
            )
        }
    }
    entry<AddRefuelRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = RIDES_SCENE)) { key ->
        val vehicles by viewModel.vehicles.collectAsState()
        RefuelFormScreen(
            vehicles = vehicles,
            onSave = viewModel::addRefuel,
        ) { onBack(key) }
    }
    entry<EditRefuelRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = RIDES_SCENE)) { key ->
        val loaded by produceState<Result<de.uhi.enia.ridesafe.data.Refuel?>?>(initialValue = null, key.id) {
            value = runCatching { viewModel.refuel(key.id) }
        }
        when (val result = loaded) {
            null -> {
                RefuelLoadingScreen(onBack = { onBack(key) }, showBack = showBack)
            }

            else -> {
                val refuel = result.getOrNull()
                if (refuel == null) {
                    RefuelUnavailableScreen(onBack = { onBack(key) }, showBack = showBack)
                } else {
                    val vehicles by viewModel.vehicles.collectAsState()
                    RefuelFormScreen(
                        vehicles = vehicles,
                        existing = refuel,
                        onSave = viewModel::updateRefuel,
                    ) { onBack(key) }
                }
            }
        }
    }
    entry<AnalysisQueueRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = RIDES_SCENE)) {
        val entries by viewModel.entries.collectAsState()
        val analysis by viewModel.analysisProgress.collectAsState()
        // Merged entries carry their stops, so flattening covers every ride a job can point at.
        val rides = remember(entries) { entries.flatMap { it.rides }.associateBy { it.id } }
        AnalysisQueueScreen(
            progress = analysis,
            rides = rides,
            onBack = { onBack(AnalysisQueueRoute) },
            showBack = showBack,
        )
    }
    entry<MergedRideDetailRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = RIDES_SCENE)) { key ->
        val stops by viewModel.groupStops(key.groupId).collectAsState(initial = null)
        // Draw one route per stop, disconnected (MRG-07); null until the stops (and their routes) load.
        val segments by produceState<List<List<LatLng>>?>(initialValue = null, stops) {
            value = stops?.takeIf { it.isNotEmpty() }?.let { viewModel.routes(it) }
        }
        val groupEvents by viewModel.groupRideEvents(key.groupId).collectAsState(initial = emptyList())
        val refuels by viewModel.attachedRefuels("g${key.groupId}").collectAsState(initial = emptyList())
        MergedRideDetailScreen(
            stops = stops,
            segments = segments,
            rideEvents = groupEvents,
            refuels = refuels,
            onOpenRefuel = { onOpen(EditRefuelRoute(it)) },
            onDetachRefuel = { viewModel.detachRefuels(listOf(it)) },
            onBack = { onBack(key) },
            onUnmergeAll = { viewModel.unmergeAll(key.groupId) },
            onUnmerge = { viewModel.unmerge(key.groupId, it) },
            showBack = showBack,
        )
    }
    entry<RideDetailRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = RIDES_SCENE)) { key ->
        val ride by viewModel.ride(key.id).collectAsState(initial = null)
        val addresses by viewModel.savedAddresses.collectAsState()
        val rideEvents by viewModel.rideEvents(key.id).collectAsState(initial = emptyList())
        val refuels by viewModel.attachedRefuels("r${key.id}").collectAsState(initial = emptyList())
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
            refuels = refuels,
            onOpenRefuel = { onOpen(EditRefuelRoute(it)) },
            onBack = { onBack(key) },
            showBack = showBack,
        )
    }
}
