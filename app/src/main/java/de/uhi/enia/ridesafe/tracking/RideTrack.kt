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
 * **Acceleration is kept at every recorded sample.** It is the sensor events are actually detected
 * from, it feeds a 2 Hz low-pass, and thinning it ahead of that filter would alias road noise
 * straight into the band the detector reads. Nothing about event sensitivity is traded here.
 *
 * The two supporting streams are thinned on the way in, which is what keeps a long ride inside the
 * heap — at 50 Hz all three, a six-hour drive is 3.2M sample objects and roughly 160 MB, enough to
 * wedge the app in continuous GC. They carry different requirements, hence separate rates:
 *
 * [rotationKeepHz] resolves the device's orientation. Staleness there cannot hide an event: a stale
 * angle θ shrinks the horizontal magnitude only by cos θ, and 40 ms at even a violent 30°/s of body
 * motion is 1.2°, or 0.02% of the reading. What it can do is leak a little gravity into the
 * horizontal plane, g·sin θ ≈ 0.02 g worst case — bounded well under the 0.25 g floor, and 25 Hz
 * leaves margin over the ~1–2 Hz at which a car body actually pitches and rolls.
 *
 * [gyroKeepHz] feeds a phone-handling gate and a yaw rate. Vehicle yaw is well under 1 Hz and
 * handling a phone lasts seconds, so 10 Hz is an order of magnitude more than either needs.
 *
 * All of this is an in-memory decision. The recording still writes every sensor at 50 Hz and the raw
 * file is untouched, so raising either rate is one parameter and a re-analysis away.
 *
 * ponytail: acceleration still scales with ride length, ~8 MB/hour, so this holds to roughly a
 * 20-hour ride rather than forever. Stream it past the detector instead of materialising it if that
 * ever becomes the limit.
 */
suspend fun readRideSamples(
    file: File,
    rotationKeepHz: Int = 25,
    gyroKeepHz: Int = 10,
): RideSamples =
    withContext(Dispatchers.IO) {
        val locations = ArrayList<LocationSample>()
        val accel = ArrayList<MotionSample>()
        val gyro = ArrayList<MotionSample>()
        val rotation = ArrayList<MotionSample>()
        // Sentinel rather than arithmetic: timestamps are elapsed-realtime nanos and always
        // positive, so the short-circuit keeps the first sample without risking an underflow.
        val rotationIntervalNanos = 1_000_000_000L / rotationKeepHz
        val gyroIntervalNanos = 1_000_000_000L / gyroKeepHz
        var lastGyro = Long.MIN_VALUE
        var lastRotation = Long.MIN_VALUE

        fun due(
            last: Long,
            now: Long,
            interval: Long,
        ) = last == Long.MIN_VALUE || now - last >= interval
        try {
            GZIPInputStream(FileInputStream(file)).bufferedReader().use { r ->
                var line = r.readLine()
                while (line != null) {
                    when (val sample = runCatching { rideSampleJson.decodeFromString<RideSample>(line) }.getOrNull()) {
                        is LocationSample -> locations.add(sample)
                        is MotionSample ->
                            when (sample.sensor) {
                                MotionSensor.ACCEL -> accel.add(sample)
                                MotionSensor.GYRO ->
                                    if (due(lastGyro, sample.t, gyroIntervalNanos)) {
                                        gyro.add(sample)
                                        lastGyro = sample.t
                                    }
                                MotionSensor.ROTATION ->
                                    if (due(lastRotation, sample.t, rotationIntervalNanos)) {
                                        rotation.add(sample)
                                        lastRotation = sample.t
                                    }
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
