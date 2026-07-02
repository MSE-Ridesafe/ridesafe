package de.uhi.enia.ridesafe.ui.screens.rides

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.data.MergedSummary
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.mergeGroupIdFor
import de.uhi.enia.ridesafe.data.summarizeMerge
import de.uhi.enia.ridesafe.tracking.processRide
import de.uhi.enia.ridesafe.tracking.processedRouteFile
import de.uhi.enia.ridesafe.tracking.readProcessedRoute
import de.uhi.enia.ridesafe.tracking.readRideLocations
import de.uhi.enia.ridesafe.tracking.reverseGeocode
import de.uhi.enia.ridesafe.tracking.ridesDir
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A ride plus its vehicle's display name (null when recorded in an unmapped/unassigned vehicle). */
data class RideRow(
    val ride: Ride,
    val vehicleName: String?,
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

    init {
        // One pass per launch: reverse-geocode any ride that has a fix but no stored address yet
        // (existing rides, plus anything a previous run couldn't geocode while offline).
        viewModelScope.launch { rideDao.needingAddresses().forEach { fillAddresses(it) } }
        // One pass per launch: Kalman-filter + simplify the GPS of any ride not processed yet, and
        // persist its distance/avg speed so the detail view loads from the DB + sidecar, not the raw file.
        viewModelScope.launch { rideDao.needingProcessing().forEach { process(it) } }
    }

    /** Rides (newest first, from the DAO) joined to their vehicle's display name for the list. */
    val rides: Flow<List<RideRow>> =
        combine(rideDao.observeAll(), vehicleDao.observeAll()) { rides, vehicles ->
            val names = vehicles.associate { it.id to it.displayTitle() }
            rides.map { RideRow(it, it.vehicleId?.let(names::get)) }
        }

    /** The list as shown in the Logbook: standalone rides plus merged rides collapsed to one entry (§3.8). */
    val entries: Flow<List<LogbookEntry>> = rides.map(::toEntries)

    fun ride(id: Long): Flow<Ride?> = rideDao.observe(id)

    /** The stops of a merged ride (MRG-04), chronological — the merged detail's source of truth. */
    fun groupStops(groupId: Long): Flow<List<Ride>> = rideDao.observeGroup(groupId)

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

    /** Process a ride's GPS (filter + simplify + sidecar) and persist its distance/avg speed; no-op if no fixes. */
    private suspend fun process(ride: Ride) {
        val metrics = processRide(getApplication(), ride) ?: return
        rideDao.setMetrics(ride.id, metrics.distanceMeters, metrics.avgSpeedMps)
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
