package de.uhi.enia.ridesafe.tracking

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/** Directory holding the per-ride sample files (see [RideRecordingEngine]). */
fun ridesDir(appContext: Context): File = File(appContext.filesDir, "rides")

// Must agree with the writer's discriminator (RideRecordingEngine); ignore unknowns for forward compat.
private val rideSampleJson =
    Json {
        classDiscriminator = "ty"
        ignoreUnknownKeys = true
    }

/**
 * Reads the GPS fixes from a ride's gzip'd NDJSON sample file, tolerating a truncated/corrupt
 * tail (a crash mid-write) by returning whatever parsed cleanly. Motion samples are skipped.
 */
suspend fun readRideLocations(file: File): List<LocationSample> =
    withContext(Dispatchers.IO) {
        val out = ArrayList<LocationSample>()
        try {
            GZIPInputStream(FileInputStream(file)).bufferedReader().use { r ->
                var line = r.readLine()
                while (line != null) {
                    (runCatching { rideSampleJson.decodeFromString<RideSample>(line) }.getOrNull() as? LocationSample)
                        ?.let(out::add)
                    line = r.readLine()
                }
            }
        } catch (e: Exception) {
            Log.w("RideRecording", "truncated sample file ${file.name}; recovered ${out.size} fixes", e)
        }
        out
    }

/** Total path length in meters, summed great-circle over consecutive fixes (ANL-02 primitive). */
fun trackDistanceMeters(locations: List<LocationSample>): Double {
    var total = 0.0
    for (i in 1 until locations.size) {
        val a = locations[i - 1]
        val b = locations[i]
        total += haversineMeters(a.lat, a.lon, b.lat, b.lon)
    }
    return total
}
