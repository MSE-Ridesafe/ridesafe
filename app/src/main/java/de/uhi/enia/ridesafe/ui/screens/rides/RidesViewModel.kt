package de.uhi.enia.ridesafe.ui.screens.rides

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.data.MergedSummary
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.mergeGroupIdFor
import de.uhi.enia.ridesafe.data.rematchRides
import de.uhi.enia.ridesafe.data.summarizeMerge
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisPipeline
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisProgress
import de.uhi.enia.ridesafe.rides.processing.processedRouteFile
import de.uhi.enia.ridesafe.rides.processing.readProcessedRoute
import de.uhi.enia.ridesafe.rides.processing.reverseGeocode
import de.uhi.enia.ridesafe.rides.recording.readRideLocations
import de.uhi.enia.ridesafe.rides.recording.ridesDir
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A ride plus its vehicle's display name (null when recorded in an unmapped/unassigned vehicle). */
data class RideRow(
    val ride: Ride,
    val vehicleName: String?,
    val startPlace: SavedAddress? = null,
    val endPlace: SavedAddress? = null,
)

/**
 * One row of the Logbook list: either a standalone ride or a merged ride collapsed into a single
 * entry (§3.8). [rideIds] is what the selection/merge machinery operates on — for a merged entry it's
 * all of its stops, since selecting a merged ride in the list means selecting the whole trip.
 */
sealed interface LogbookEntry {
    val sortEpochMs: Long
    val key: String
    val rides: List<Ride>

    val rideIds: List<Long> get() = rides.map { it.id }

    data class Single(
        val row: RideRow,
    ) : LogbookEntry {
        override val sortEpochMs get() = row.ride.startedAtEpochMs
        override val key get() = "r${row.ride.id}"
        override val rides get() = listOf(row.ride)
    }

    data class Merged(
        val groupId: Long,
        val stops: List<RideRow>, // chronological (oldest first)
        val summary: MergedSummary,
        val vehicleName: String?,
    ) : LogbookEntry {
        // Sort/day-group a merged trip by its most recent stop, so it slots into the newest-first list.
        override val sortEpochMs get() = stops.maxOf { it.ride.startedAtEpochMs }
        override val key get() = "g$groupId"
        override val rides get() = stops.map { it.ride }
    }
}

/**
 * Rides state, app-scoped (hoisted in RidesafeApp) so the list and detail screens share one
 * instance. The Room [Flow]s are the single source of truth, so a finished recording shows up
 * in the list automatically.
 */
class RidesViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = RidesafeDatabase.getInstance(app)
    private val rideDao = db.rideDao()
    private val vehicleDao = db.vehicleDao()
    private val savedAddressDao = db.savedAddressDao()
    private val rideEventDao = db.rideEventDao()

    private val pipeline = RideAnalysisPipeline(app, db)

    /** The analysis queue (ANL-03), for the Rides status bar, the queue screen and the detail notice. */
    val analysisProgress: StateFlow<RideAnalysisProgress> = pipeline.progress

    init {
        // One pass per launch: geocode any ride still missing an address and match every ride's
        // endpoints to the saved places. Runs on its own rather than waiting for the analysis
        // pipeline below, which can take a minute on a big backfill.
        viewModelScope.launch { refreshPlaces() }
        // Everything derived from a ride's sample file — GPS filtering and metrics (ANL-02), then
        // driving-event detection (ANL-01) — as one ordered pipeline per ride, several rides at a
        // time. Re-runs whenever the set of finished rides changes, so a ride recorded while the app
        // is open is analyzed without waiting for a relaunch; writing metrics or events doesn't
        // change that set, so the pipeline can't re-trigger itself.
        viewModelScope.launch {
            rideDao
                .observeFinished()
                .map { rides -> rides.map(Ride::id).toSet() }
                .distinctUntilChanged()
                .collect {
                    pipeline.runPending()
                    // Analysis can move a ride's endpoints off a bad first/last fix, which leaves
                    // its address and saved place describing somewhere it never was; both are
                    // cleared when that happens, so this fills them back in from the corrected
                    // position while the user is still in the app.
                    refreshPlaces()
                }
        }
    }

    /** Fill in any missing ride address (DR-RID) and re-match every ride to the saved places (ADR-07). */
    private suspend fun refreshPlaces() {
        rideDao.needingAddresses().forEach { fillAddresses(it) }
        rematchRides(rideDao, savedAddressDao)
    }

    /**
     * The garage (DR-VEH), exposed so a detail screen can resolve the ride's vehicle. The fuel
     * estimate (ANL-03) is stored uncalibrated and scaled onto the vehicle on read, so the screen
     * needs the vehicle itself, not just the name [RideRow] already carries. Whole list rather than
     * a per-id query for the same reason the saved places are: a garage is a handful of rows.
     */
    val vehicles: StateFlow<List<Vehicle>> =
        vehicleDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Saved places (DR-ADR), exposed so the detail screen can resolve a ride's matched endpoints. */
    val savedAddresses: StateFlow<List<SavedAddress>> =
        savedAddressDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Rides carrying at least one detected driving event (ANL-01), for the logbook's events filter. */
    val ridesWithEvents: StateFlow<Set<Long>> =
        rideEventDao
            .observeRideIdsWithEvents()
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _filter = MutableStateFlow(RideFilter())

    /**
     * The logbook's search + filter criteria (LOG-06, LOG-07, LOG-11 … LOG-15). Kept in the
     * ViewModel, not the screen: the list entry leaves composition while a ride detail is open, so
     * screen-local state would drop the user's filters every time they looked at a ride and came
     * back. It is deliberately not persisted — a fresh launch starts on the whole logbook.
     */
    val filter: StateFlow<RideFilter> = _filter.asStateFlow()

    fun setFilter(value: RideFilter) {
        _filter.value = value
    }

    /**
     * Finished rides (newest first) joined to their vehicle name and the saved places their endpoints
     * matched. The ride being recorded right now is deliberately absent — it only becomes a logbook
     * entry once it is finalized; the dashboard is where a running ride is shown live.
     */
    private val rides: Flow<List<RideRow>> =
        combine(rideDao.observeFinished(), vehicleDao.observeAll(), savedAddressDao.observeAll()) { rides, vehicles, addresses ->
            val names = vehicles.associate { it.id to it.displayTitle() }
            val places = addresses.associateBy { it.id }
            rides.map {
                RideRow(
                    ride = it,
                    vehicleName = it.vehicleId?.let(names::get),
                    startPlace = it.startAddressId?.let(places::get),
                    endPlace = it.endAddressId?.let(places::get),
                )
            }
        }

    /**
     * The list as shown in the Logbook: standalone rides plus merged rides collapsed to one entry (§3.8).
     * Hot + prefetched: the combine/fold runs on Default (off the frame thread) and starts at VM creation
     * (app launch), so the first visit to the Rides tab reads an already-loaded value instead of paying the
     * cold DB-open + query + fold mid-transition. Eagerly (not WhileSubscribed) keeps it warm app-wide.
     */
    val entries: StateFlow<List<LogbookEntry>> =
        rides
            .map(::toEntries)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun ride(id: Long): Flow<Ride?> = rideDao.observe(id)

    /** The stops of a merged ride (MRG-04), chronological — the merged detail's source of truth. */
    fun groupStops(groupId: Long): Flow<List<Ride>> = rideDao.observeGroup(groupId)

    /** A ride's detected driving events (ANL-01), for the map's marker layer. */
    fun rideEvents(rideId: Long): Flow<List<RideEvent>> = rideEventDao.observeForRide(rideId)

    /** Every stop's driving events for a merged ride, so its map covers the whole trip (MRG-07). */
    fun groupRideEvents(groupId: Long): Flow<List<RideEvent>> = rideEventDao.observeForGroup(groupId)

    /** Per-stop routes for the merged map, each drawn as its own disconnected polyline (MRG-07). */
    suspend fun routes(stops: List<Ride>): List<List<LatLng>> = stops.map { route(it) }

    /** Merge the given rides into one trip (MRG-01); the smallest id becomes the shared group id (MRG-10). */
    fun merge(rideIds: List<Long>) {
        if (rideIds.size < 2) return
        viewModelScope.launch { rideDao.setMergeGroup(mergeGroupIdFor(rideIds), rideIds) }
    }

    /** Peel the given stops off a merged ride (MRG-11); if ≤1 stop is left, dissolve the group entirely. */
    fun unmerge(
        groupId: Long,
        stopIds: List<Long>,
    ) = viewModelScope.launch {
        rideDao.setMergeGroup(null, stopIds)
        val remaining = rideDao.groupMembers(groupId)
        if (remaining.size <= 1) rideDao.setMergeGroup(null, remaining.map { it.id })
    }

    /** Restore every stop of a merged ride to a standalone ride (MRG-03). */
    fun unmergeAll(groupId: Long) =
        viewModelScope.launch {
            rideDao.setMergeGroup(null, rideDao.groupMembers(groupId).map { it.id })
        }

    /** Fold rides into list entries: each merge group (≥2 stops) collapses to one Merged entry, rest stay Single. */
    private fun toEntries(rows: List<RideRow>): List<LogbookEntry> {
        val entries = mutableListOf<LogbookEntry>()
        val merged = HashSet<Long>()
        rows
            .filter { it.ride.mergeGroupId != null }
            .groupBy { it.ride.mergeGroupId!! }
            .forEach { (groupId, stops) ->
                if (stops.size < 2) return@forEach // a lone tagged ride falls through to Single below
                val ordered = stops.sortedBy { it.ride.startedAtEpochMs }
                ordered.forEach { merged.add(it.ride.id) }
                entries.add(
                    LogbookEntry.Merged(
                        groupId = groupId,
                        stops = ordered,
                        summary = summarizeMerge(ordered.map { it.ride }),
                        vehicleName = ordered.first().vehicleName,
                    ),
                )
            }
        rows.filterNot { it.ride.id in merged }.forEach { entries.add(LogbookEntry.Single(it)) }
        return entries.sortedByDescending { it.sortEpochMs }
    }

    /**
     * The route to draw for a ride (off the main thread): the processed, RDP-simplified sidecar when
     * it exists (fast path — no raw-file read), else the raw fixes for a ride not processed yet.
     */
    suspend fun route(ride: Ride): List<LatLng> =
        withContext(Dispatchers.IO) {
            readProcessedRoute(processedRouteFile(getApplication(), ride))
                ?: run {
                    val file = File(ridesDir(getApplication()), ride.sampleFile)
                    if (file.exists()) readRideLocations(file).map { LatLng(it.lat, it.lon) } else emptyList()
                }
        }

    /** Reverse-geocode whichever endpoints lack an address and persist; a no-op if nothing resolves. */
    private suspend fun fillAddresses(ride: Ride) {
        val app = getApplication<Application>()
        val start =
            ride.startAddress
                ?: ride.startLat?.let { lat -> ride.startLon?.let { lon -> reverseGeocode(app, lat, lon) } }
        val end =
            ride.endAddress
                ?: ride.endLat?.let { lat -> ride.endLon?.let { lon -> reverseGeocode(app, lat, lon) } }
        if (start != ride.startAddress || end != ride.endAddress) {
            rideDao.setAddresses(ride.id, start, end)
        }
    }
}
