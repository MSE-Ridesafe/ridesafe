package de.uhi.enia.ridesafe.ui.screens.rides

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RidesafeDatabase
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A ride plus its vehicle's display name (null when recorded in an unmapped/unassigned vehicle). */
data class RideRow(
    val ride: Ride,
    val vehicleName: String?,
)

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

    fun ride(id: Long): Flow<Ride?> = rideDao.observe(id)

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
