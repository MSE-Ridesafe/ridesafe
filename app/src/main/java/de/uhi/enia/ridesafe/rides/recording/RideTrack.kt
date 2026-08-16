package de.uhi.enia.ridesafe.rides.recording

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException

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
        } catch (e: CancellationException) {
            throw e // cancellation is not a corrupt file; let it unwind
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
 * Reads a ride's whole sample stream into memory, split per stream, tolerating a truncated or
 * corrupt tail the same way [readRideLocations] does. Every sensor is kept at the recorded rate —
 * nothing is thinned.
 *
 * This materialises the ride, so it costs roughly 25 MB per hour of driving. That is fine for tests
 * and for anything working with a single short ride; the analysis path deliberately does not use it
 * and streams via [forEachSampleInTimeOrder] instead, so that a six-hour ride costs the same as a
 * six-minute one.
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
                        is LocationSample -> {
                            locations.add(sample)
                        }

                        is MotionSample -> {
                            when (sample.sensor) {
                                MotionSensor.ACCEL -> accel.add(sample)
                                MotionSensor.GYRO -> gyro.add(sample)
                                MotionSensor.ROTATION -> rotation.add(sample)
                            }
                        }

                        null -> {
                            Unit
                        } // unparseable line; skip it
                    }
                    line = r.readLine()
                }
            }
        } catch (e: CancellationException) {
            throw e // cancellation is not a corrupt file; let it unwind
        } catch (e: Exception) {
            Log.w("RideRecording", "truncated sample file ${file.name}; recovered ${accel.size} accel samples", e)
        }
        RideSamples(locations, accel, gyro, rotation)
    }

/**
 * Reads a ride's samples and hands them to [onSample] in true timestamp order, holding only a
 * bounded window in memory rather than the whole ride.
 *
 * Reordering is needed because the file is not time-ordered. The sensor FIFO batches motion and
 * flushes it up to its latency later, so a motion sample can be written thousands of lines after a
 * GPS fix that is older than it. Each sensor's own stream stays monotonic, so a sample is safe to
 * release once everything still to come is newer than it — which is what [reorderWindowNanos]
 * expresses. It only has to exceed the recorder's batch latency; the default is triple it.
 *
 * The heap therefore holds one window's worth of samples — about 2,200 at 150 samples/s, some
 * 110 KB — regardless of whether the ride is ten minutes or ten hours. A truncated or corrupt tail
 * is tolerated the same way [readRideLocations] tolerates it: whatever parsed cleanly is delivered.
 *
 * @param file the ride's gzip'd NDJSON sample file.
 * @param reorderWindowNanos how far back in time a sample may still arrive. Only needs to exceed the
 * recorder's sensor-batch latency; the default is triple it. Raise it and the heap holds
 * proportionally more samples, buying safety margin with memory; lower it past the batch latency and
 * samples are emitted before their older siblings arrive, so nothing is dropped but the time-ordering
 * guarantee is lost.
 * @param onSample receives every parsed sample in ascending timestamp order.
 */
fun forEachSampleInTimeOrder(
    file: File,
    reorderWindowNanos: Long = 15_000_000_000L,
    onSample: (RideSample) -> Unit,
) {
    // Explicit comparator rather than compareBy: the latter boxes the Long on every comparison, and
    // this runs tens of millions of times on a long ride.
    val pending = PriorityQueue(1024) { a: RideSample, b: RideSample -> a.t.compareTo(b.t) }
    var newest = Long.MIN_VALUE
    try {
        GZIPInputStream(FileInputStream(file)).bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val sample = runCatching { rideSampleJson.decodeFromString<RideSample>(line) }.getOrNull()
                if (sample != null) {
                    pending.add(sample)
                    if (sample.t > newest) newest = sample.t
                    while (pending.isNotEmpty() && newest - pending.peek().t > reorderWindowNanos) {
                        onSample(pending.poll())
                    }
                }
                line = reader.readLine()
            }
        }
    } catch (e: CancellationException) {
        throw e // cancellation is not a corrupt file; let it unwind
    } catch (e: Exception) {
        Log.w("RideRecording", "truncated sample file ${file.name}; delivering what parsed", e)
    }
    while (pending.isNotEmpty()) onSample(pending.poll())
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
