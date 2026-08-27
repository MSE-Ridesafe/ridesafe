package de.uhi.enia.ridesafe.ui.screens.rides

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.data.MergeCheck
import de.uhi.enia.ridesafe.data.MergedSummary
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.canMerge
import de.uhi.enia.ridesafe.data.consolidateSavedAddressDuplicates
import de.uhi.enia.ridesafe.data.mergeGroupIdFor
import de.uhi.enia.ridesafe.data.rematchRides
import de.uhi.enia.ridesafe.data.summarizeMerge
import de.uhi.enia.ridesafe.rides.RideDataCoordinator
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

data class RefuelRow(
    val refuel: Refuel,
    val vehicleName: String?,
)

enum class LogbookOperation { MERGED, ATTACHED, DETACHED, DELETED }

sealed interface LogbookOperationState {
    data object Idle : LogbookOperationState

    data object Running : LogbookOperationState

    data class Success(
        val operation: LogbookOperation,
    ) : LogbookOperationState

    data object Error : LogbookOperationState
}

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
    private val refuelDao = db.refuelDao()

    private val pipeline = RideAnalysisPipeline(app, db)
    private val rideExporter = RideExporter(app)

    private val exportController =
        RideExportController(
            scope = viewModelScope,
            operation = rideExporter::export,
            onFailure = { Log.e("RideExport", "Could not export selected rides", it) },
        )
    val exportState: StateFlow<RideExportState> = exportController.state

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
                .observeAll()
                .map { rides -> rides.filter { it.endedAtEpochMs != null }.map(Ride::id).toSet() }
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
        db.withTransaction {
            consolidateSavedAddressDuplicates(rideDao, savedAddressDao)
        }
        rideDao.needingAddresses().forEach { fillAddresses(it) }
        rematchRides(rideDao, savedAddressDao)
    }

    /** Saved places (DR-ADR), exposed so the detail screen can resolve a ride's matched endpoints. */
    val savedAddresses: StateFlow<List<SavedAddress>> =
        savedAddressDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val vehicles: StateFlow<List<Vehicle>> =
        vehicleDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Rides (newest first) joined to their vehicle name and the saved places their endpoints matched. */
    private val rides: Flow<List<RideRow>> =
        combine(rideDao.observeAll(), vehicles, savedAddressDao.observeAll()) { rides, vehicles, addresses ->
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

    private val refuelRows: StateFlow<List<RefuelRow>> =
        combine(refuelDao.observeAll(), vehicles) { refuels, vehicles ->
            val names = vehicles.associate { it.id to it.displayTitle() }
            refuels.map { refuel ->
                RefuelRow(
                    refuel = refuel,
                    vehicleName = names[refuel.vehicleId],
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val timeline: StateFlow<List<TimelineEntry>> =
        combine(entries, refuelRows, ::buildTimeline)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun attachedRefuels(logbookKey: String): Flow<List<RefuelRow>> =
        timeline
            .map { timeline ->
                timeline
                    .filterIsInstance<TimelineEntry.RideEntry>()
                    .firstOrNull { it.stableKey == logbookKey }
                    ?.refuels
                    .orEmpty()
            }.distinctUntilChanged()

    private val _logbookOperationState = MutableStateFlow<LogbookOperationState>(LogbookOperationState.Idle)
    val logbookOperationState: StateFlow<LogbookOperationState> = _logbookOperationState.asStateFlow()

    fun addRefuel(
        refuel: Refuel,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(
                runCatching {
                    db.withTransaction {
                        val id = refuelDao.insert(refuel)
                        updateVehicleMileageIfNewest(refuel.copy(id = id))
                    }
                }.onFailure { Log.e("RefuelInsert", "Could not insert refuel", it) },
            )
        }
    }

    /** Missing Ride anchors have no logical parent; normalize them for editing so they behave unattached. */
    suspend fun refuel(id: Long): Refuel? =
        refuelDao.getById(id)?.let { refuel ->
            val anchor = refuel.journeyAnchorRideId ?: return@let refuel
            if (rideDao.byIds(listOf(anchor)).isEmpty()) refuel.copy(journeyAnchorRideId = null) else refuel
        }

    fun updateRefuel(
        refuel: Refuel,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(
                runCatching {
                    db.withTransaction {
                        refuelDao.update(refuel)
                        updateVehicleMileageIfNewest(refuel)
                    }
                }.onFailure { Log.e("RefuelUpdate", "Could not update refuel ${refuel.id}", it) },
            )
        }
    }

    /** A Refuel updates Garage mileage only when it is the newest event for that vehicle. */
    private suspend fun updateVehicleMileageIfNewest(refuel: Refuel) {
        if (refuelDao.newestForVehicle(refuel.vehicleId)?.id != refuel.id) return
        val mileageKm = odometerMetersToVehicleMileageKm(refuel.odometerMeters) ?: return
        vehicleDao.updateMileage(refuel.vehicleId, mileageKm, System.currentTimeMillis())
    }

    fun ride(id: Long): Flow<Ride?> = rideDao.observe(id)

    /** The stops of a merged ride (MRG-04), chronological — the merged detail's source of truth. */
    fun groupStops(groupId: Long): Flow<List<Ride>> = rideDao.observeGroup(groupId)

    /** A ride's detected driving events (ANL-01), for the map's marker layer. */
    fun rideEvents(rideId: Long): Flow<List<RideEvent>> = rideEventDao.observeForRide(rideId)

    /** Every stop's driving events for a merged ride, so its map covers the whole trip (MRG-07). */
    fun groupRideEvents(groupId: Long): Flow<List<RideEvent>> = rideEventDao.observeForGroup(groupId)

    /** Per-stop routes for the merged map, each drawn as its own disconnected polyline (MRG-07). */
    suspend fun routes(stops: List<Ride>): List<List<LatLng>> = stops.map { route(it) }

    /** Merge rides and attach explicitly selected compatible Refuels in one Room transaction. */
    fun merge(
        rideIds: List<Long>,
        refuelIds: List<Long>,
    ) = startLogbookOperation(LogbookOperation.MERGED) {
        db.withTransaction {
            val allRides = rideDao.all()
            val selectedRides = rideDao.byIds(rideIds.distinct())
            require(selectedRides.size == rideIds.distinct().size)
            require(canMerge(selectedRides.mapTo(hashSetOf()) { it.id }, allRides) == MergeCheck.OK)
            val selectedRefuels = if (refuelIds.isEmpty()) emptyList() else refuelDao.byIds(refuelIds.distinct())
            require(selectedRefuels.size == refuelIds.distinct().size)
            val vehicleId = selectedRides.first().vehicleId
            require(selectedRefuels.all { it.vehicleId == vehicleId })
            val liveRideIds = allRides.mapTo(hashSetOf()) { it.id }
            val selectedRideIds = selectedRides.mapTo(hashSetOf()) { it.id }
            require(
                selectedRefuels.none { refuel ->
                    refuel.journeyAnchorRideId?.let { it in liveRideIds && it !in selectedRideIds } == true
                },
            )

            // Selecting one already-combined logical entry plus Refuels only needs association work.
            // Avoid a no-op Ride update (and its separate Flow invalidation) in that case.
            val existingGroupId = selectedRides.first().mergeGroupId
            val alreadyOneCompleteGroup =
                existingGroupId != null &&
                    selectedRides.all { it.mergeGroupId == existingGroupId } &&
                    allRides.filter { it.mergeGroupId == existingGroupId }.mapTo(hashSetOf()) { it.id } == selectedRideIds
            if (!alreadyOneCompleteGroup) {
                rideDao.setMergeGroup(mergeGroupIdFor(selectedRideIds), selectedRideIds.toList())
            }
            selectedRefuels
                .filter { it.journeyAnchorRideId !in selectedRideIds }
                .forEach { refuelDao.setJourneyAnchor(it.id, closestRideAnchor(it, selectedRides).id) }
        }
    }

    fun attachRefuels(
        targetRideIds: List<Long>,
        refuelIds: List<Long>,
    ) = startLogbookOperation(LogbookOperation.ATTACHED) {
        db.withTransaction {
            val allRides = rideDao.all()
            val targetRides = rideDao.byIds(targetRideIds.distinct())
            val selectedRefuels = refuelDao.byIds(refuelIds.distinct())
            require(targetRides.isNotEmpty() && targetRides.size == targetRideIds.distinct().size)
            require(selectedRefuels.isNotEmpty() && selectedRefuels.size == refuelIds.distinct().size)
            val targetIds = targetRides.mapTo(hashSetOf()) { it.id }
            if (targetRides.size == 1) {
                require(targetRides.single().mergeGroupId == null)
            } else {
                val groupId = targetRides.first().mergeGroupId
                require(groupId != null && targetRides.all { it.mergeGroupId == groupId })
                require(allRides.filter { it.mergeGroupId == groupId }.mapTo(hashSetOf()) { it.id } == targetIds)
            }
            val vehicleId = targetRides.first().vehicleId
            require(vehicleId != null && targetRides.all { it.vehicleId == vehicleId })
            require(selectedRefuels.all { it.vehicleId == vehicleId })
            val liveRideIds = allRides.mapTo(hashSetOf()) { it.id }
            require(
                selectedRefuels.none { refuel ->
                    refuel.journeyAnchorRideId?.let { it in liveRideIds && it !in targetIds } == true
                },
            )
            val needingAttachment = selectedRefuels.filter { it.journeyAnchorRideId !in targetIds }
            require(needingAttachment.isNotEmpty())
            needingAttachment.forEach { refuelDao.setJourneyAnchor(it.id, closestRideAnchor(it, targetRides).id) }
        }
    }

    fun detachRefuels(refuelIds: List<Long>) =
        startLogbookOperation(LogbookOperation.DETACHED) {
            db.withTransaction {
                val selected = refuelDao.byIds(refuelIds.distinct())
                val liveRideIds = rideDao.all().mapTo(hashSetOf()) { it.id }
                require(selected.isNotEmpty() && selected.size == refuelIds.distinct().size)
                require(selected.all { refuel -> refuel.journeyAnchorRideId?.let(liveRideIds::contains) == true })
                refuelDao.clearJourneyAnchor(selected.map { it.id })
            }
        }

    /**
     * Permanently remove the selected logical entries. A merged entry supplies every physical ride
     * id in the group. Refuels are independent records: deleting a ride only detaches its linked
     * refuels; a refuel is deleted only when its own id is explicitly selected.
     */
    fun deleteEntries(
        rideIds: List<Long>,
        refuelIds: List<Long>,
    ) = startLogbookOperation(LogbookOperation.DELETED) {
        val distinctRideIds = rideIds.distinct()
        val distinctRefuelIds = refuelIds.distinct()
        require(distinctRideIds.isNotEmpty() || distinctRefuelIds.isNotEmpty())

        RideDataCoordinator.withRides(distinctRideIds) {
            val ridesToDelete = if (distinctRideIds.isEmpty()) emptyList() else rideDao.byIds(distinctRideIds)
            val refuelsToDelete = if (distinctRefuelIds.isEmpty()) emptyList() else refuelDao.byIds(distinctRefuelIds)
            require(ridesToDelete.size == distinctRideIds.size)
            require(refuelsToDelete.size == distinctRefuelIds.size)
            // The recorder owns active rides and their open streams; they cannot be deleted here.
            require(ridesToDelete.all { it.endedAtEpochMs != null })

            db.withTransaction {
                if (distinctRideIds.isNotEmpty()) {
                    refuelDao.clearJourneyAnchorsForRides(distinctRideIds)
                }
                if (distinctRefuelIds.isNotEmpty()) {
                    refuelDao.deleteByIds(distinctRefuelIds)
                }
                if (distinctRideIds.isNotEmpty()) {
                    rideDao.deleteByIds(distinctRideIds)
                }
            }

            // Database rows are the source of truth. Clean up their private sample and derived
            // files while the same per-ride locks used by analysis/export are still held.
            val directory = ridesDir(getApplication())
            ridesToDelete.forEach { ride ->
                runCatching {
                    safeRideFile(directory, ride.sampleFile)?.delete()
                    safePrivateFile(directory, processedRouteFile(getApplication(), ride))?.delete()
                }.onFailure {
                    // The database deletion has committed, so a stale private sidecar must not turn
                    // a successful user action into a misleading failure. It is harmless and can be
                    // removed by later maintenance.
                    Log.w("LogbookDelete", "Could not clean private files for ride ${ride.id}", it)
                }
            }
        }
    }

    private fun safeRideFile(
        directory: File,
        relativeName: String,
    ): File? {
        val root = directory.canonicalFile
        val candidate = File(root, relativeName).canonicalFile
        return candidate.takeIf { it.parentFile == root }
    }

    private fun safePrivateFile(
        directory: File,
        file: File,
    ): File? {
        val root = directory.canonicalFile
        val candidate = file.canonicalFile
        return candidate.takeIf { it.parentFile == root }
    }

    fun consumeLogbookOperationResult() {
        if (_logbookOperationState.value is LogbookOperationState.Success ||
            _logbookOperationState.value == LogbookOperationState.Error
        ) {
            _logbookOperationState.value = LogbookOperationState.Idle
        }
    }

    private fun startLogbookOperation(
        success: LogbookOperation,
        block: suspend () -> Unit,
    ) {
        if (_logbookOperationState.value != LogbookOperationState.Idle) return
        _logbookOperationState.value = LogbookOperationState.Running
        viewModelScope.launch {
            _logbookOperationState.value =
                runCatching { block() }
                    .fold(
                        onSuccess = { LogbookOperationState.Success(success) },
                        onFailure = {
                            Log.e("LogbookOperation", "Could not complete $success", it)
                            LogbookOperationState.Error
                        },
                    )
        }
    }

    /** Export one immutable logical-selection snapshot; repeated taps while busy are ignored. */
    fun export(
        requests: List<RideExportRequest>,
        format: RideExportFormat,
    ) {
        exportController.start(requests, format)
    }

    fun consumeExportResult() {
        exportController.consumeResult()
    }

    /** Peel the given stops off a merged ride (MRG-11); if ≤1 stop is left, dissolve the group entirely. */
    fun unmerge(
        groupId: Long,
        stopIds: List<Long>,
    ) = viewModelScope.launch {
        db.withTransaction {
            val members = rideDao.groupMembers(groupId)
            val memberIds = members.mapTo(hashSetOf()) { it.id }
            val peeledIds = stopIds.distinct()
            if (peeledIds.isEmpty() || !peeledIds.all(memberIds::contains)) return@withTransaction

            rideDao.setMergeGroup(null, peeledIds)
            refuelDao.clearJourneyAnchorsForRides(peeledIds)

            val remaining = rideDao.groupMembers(groupId)
            if (remaining.size <= 1) {
                val remainingIds = remaining.map { it.id }
                if (remainingIds.isNotEmpty()) {
                    rideDao.setMergeGroup(null, remainingIds)
                    refuelDao.clearJourneyAnchorsForRides(remainingIds)
                }
            }
        }
    }

    /** Restore every stop of a merged ride to a standalone ride (MRG-03). */
    fun unmergeAll(groupId: Long) =
        viewModelScope.launch {
            db.withTransaction {
                val memberIds = rideDao.groupMembers(groupId).map { it.id }
                if (memberIds.isNotEmpty()) {
                    rideDao.setMergeGroup(null, memberIds)
                    refuelDao.clearJourneyAnchorsForRides(memberIds)
                }
            }
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

/** A heterogeneous Rides-page event; ride-only behavior stays inside [LogbookEntry]. */
sealed interface TimelineEntry {
    val sortEpochMs: Long
    val stableKey: String

    data class RideEntry(
        val entry: LogbookEntry,
        val refuels: List<RefuelRow> = emptyList(),
    ) : TimelineEntry {
        override val sortEpochMs get() = entry.sortEpochMs
        override val stableKey get() = entry.key
    }

    data class RefuelEntry(
        val row: RefuelRow,
    ) : TimelineEntry {
        override val sortEpochMs get() = row.refuel.timestampEpochMs
        override val stableKey get() = "f${row.refuel.id}"
    }
}

/** Newest first; exact ties put rides before refuels, then use the stable persistent key. */
val timelineEntryComparator =
    compareByDescending<TimelineEntry> { it.sortEpochMs }
        .thenBy { if (it is TimelineEntry.RideEntry) 0 else 1 }
        .thenByDescending { it.stableKey }

fun rideLogbookEntries(timeline: List<TimelineEntry>): List<LogbookEntry> = timeline.mapNotNull { (it as? TimelineEntry.RideEntry)?.entry }

fun timelineSelectionKeys(timeline: List<TimelineEntry>): Set<String> = timeline.mapTo(linkedSetOf()) { it.stableKey }

fun visibleTimelineSelectionKeys(timeline: List<TimelineEntry>): Set<String> =
    timeline.flatMapTo(linkedSetOf()) { entry ->
        listOf(entry.stableKey) +
            if (entry is TimelineEntry.RideEntry && entry.entry is LogbookEntry.Single) {
                entry.refuels.map { "f${it.refuel.id}" }
            } else {
                emptyList()
            }
    }

fun selectedRefuels(
    timeline: List<TimelineEntry>,
    selectedKeys: Set<String>,
): List<Refuel> =
    timeline
        .flatMap { entry ->
            when (entry) {
                is TimelineEntry.RefuelEntry -> listOf(entry.row)
                is TimelineEntry.RideEntry -> entry.refuels
            }
        }.filter { "f${it.refuel.id}" in selectedKeys }
        .map { it.refuel }

fun selectedRideLogbookEntries(
    timeline: List<TimelineEntry>,
    selectedKeys: Set<String>,
): List<LogbookEntry> = rideLogbookEntries(timeline).filter { it.key in selectedKeys }

fun buildTimeline(
    rideEntries: List<LogbookEntry>,
    refuelRows: List<RefuelRow>,
): List<TimelineEntry> {
    val entryKeyByRideId =
        buildMap {
            rideEntries.forEach { entry -> entry.rideIds.forEach { put(it, entry.key) } }
        }
    val attachedByKey =
        refuelRows
            .mapNotNull { row ->
                row.refuel.journeyAnchorRideId
                    ?.let(entryKeyByRideId::get)
                    ?.let { it to row }
            }.groupBy({ it.first }, { it.second })
    val attachedIds = attachedByKey.values.flatten().mapTo(hashSetOf()) { it.refuel.id }
    return buildList {
        rideEntries.forEach { entry ->
            add(
                TimelineEntry.RideEntry(
                    entry,
                    attachedByKey[entry.key].orEmpty().sortedWith(
                        compareBy<RefuelRow> { it.refuel.timestampEpochMs }.thenBy { it.refuel.id },
                    ),
                ),
            )
        }
        refuelRows.filterNot { it.refuel.id in attachedIds }.forEach { add(TimelineEntry.RefuelEntry(it)) }
    }.sortedWith(timelineEntryComparator)
}
