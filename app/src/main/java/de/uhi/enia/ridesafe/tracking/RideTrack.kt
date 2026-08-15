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

/**
 * One ride's samples, split per stream. Each list is time-ordered even though the file is not:
 * motion is batched in the sensor FIFO and written up to seconds after it was sampled, so it
 * interleaves with the live-written GPS. Each *sensor's own* stream stays monotonic, which is
 * exactly why they're kept apart here — no global sort needed downstream.
 */
data class RideSamples(
    val locations: List<LocationSample> = emptyList(),
    val accel: List<MotionSample> = emptyList(),
    val gyro: List<MotionSample> = emptyList(),
    val rotation: List<MotionSample> = emptyList(),
)

/**
 * Reads a ride's full sample stream for the driving-event analysis (ANL-01), tolerating a
 * truncated/corrupt tail the same way [readRideLocations] does. Prefer [readRideLocations] when
 * only the track is needed — it skips motion, which is ~98% of the lines.
 *
 * ponytail: holds the motion samples as objects, ~25 MB for a 40-minute ride. Fine for typical
 * rides; if a multi-hour ride ever OOMs, subsample the rotation stream to ~1 Hz (a phone in a
 * mount barely rotates) before reaching for primitive arrays.
 */
suspend fun readRideSamples(file: File): RideSamples =
    withContext(Dispatchers.IO) {
        val locations = ArrayList<LocationSample>()
        val accel = ArrayList<MotionSample>()
        val gyro = ArrayList<MotionSample>()
        val rotation = ArrayList<MotionSample>()
        try {
            GZIPInputStream(FileInputStream(file)).bufferedReader().use { r ->
                var line = r.readLine()
                while (line != null) {
                    when (val sample = runCatching { rideSampleJson.decodeFromString<RideSample>(line) }.getOrNull()) {
                        is LocationSample -> locations.add(sample)
                        is MotionSample ->
                            when (sample.sensor) {
                                MotionSensor.ACCEL -> accel.add(sample)
                                MotionSensor.GYRO -> gyro.add(sample)
                                MotionSensor.ROTATION -> rotation.add(sample)
                            }
                        null -> Unit // unparseable line; skip it
                    }
                    line = r.readLine()
                }
            }
        } catch (e: Exception) {
            Log.w("RideRecording", "truncated sample file ${file.name}; recovered ${accel.size} accel samples", e)
        }
        RideSamples(locations, accel, gyro, rotation)
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
