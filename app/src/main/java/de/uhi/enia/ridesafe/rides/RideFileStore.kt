package de.uhi.enia.ridesafe.rides

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.rides.processing.processedRouteFile
import de.uhi.enia.ridesafe.rides.processing.readProcessedRoute
import de.uhi.enia.ridesafe.rides.recording.readRideLocations
import de.uhi.enia.ridesafe.rides.recording.ridesDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads and deletes a ride's private files — the raw sample stream and the processed-route
 * sidecar — with canonical-path guards so a corrupted or malicious sampleFile name can never
 * reach outside the rides directory.
 */
class RideFileStore(
    private val appContext: Context,
) {
    /**
     * The route to draw for a ride (off the main thread): the processed, RDP-simplified sidecar when
     * it exists (fast path — no raw-file read), else the raw fixes for a ride not processed yet.
     */
    suspend fun route(ride: Ride): List<LatLng> =
        withContext(Dispatchers.IO) {
            readProcessedRoute(processedRouteFile(appContext, ride))
                ?: run {
                    val file = File(ridesDir(appContext), ride.sampleFile)
                    if (file.exists()) readRideLocations(file).map { LatLng(it.lat, it.lon) } else emptyList()
                }
        }

    /**
     * Delete a deleted ride's sample and sidecar files. Failures are logged, never thrown: the
     * database deletion has committed, so a stale private sidecar must not turn a successful user
     * action into a misleading failure. It is harmless and can be removed by later maintenance.
     */
    fun deletePrivateFiles(ride: Ride) {
        val directory = ridesDir(appContext)
        runCatching {
            safeRideFile(directory, ride.sampleFile)?.delete()
            safePrivateFile(directory, processedRouteFile(appContext, ride))?.delete()
        }.onFailure {
            Log.w("LogbookDelete", "Could not clean private files for ride ${ride.id}", it)
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
}
