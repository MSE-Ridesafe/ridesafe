package de.uhi.enia.ridesafe.ui.screens.rides

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.tracking.LocationSample
import de.uhi.enia.ridesafe.tracking.readRideLocations
import de.uhi.enia.ridesafe.tracking.ridesDir
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    /** Rides (newest first, from the DAO) joined to their vehicle's display name for the list. */
    val rides: Flow<List<RideRow>> =
        combine(rideDao.observeAll(), vehicleDao.observeAll()) { rides, vehicles ->
            val names = vehicles.associate { it.id to it.displayTitle() }
            rides.map { RideRow(it, it.vehicleId?.let(names::get)) }
        }

    fun ride(id: Long): Flow<Ride?> = rideDao.observe(id)

    /** Read a ride's recorded GPS track from its sample file (off the main thread). */
    suspend fun track(ride: Ride): List<LocationSample> =
        withContext(Dispatchers.IO) {
            val file = File(ridesDir(getApplication()), ride.sampleFile)
            if (file.exists()) readRideLocations(file) else emptyList()
        }
}
