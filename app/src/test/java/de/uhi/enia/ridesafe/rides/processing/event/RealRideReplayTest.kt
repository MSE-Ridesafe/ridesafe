package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import de.uhi.enia.ridesafe.rides.recording.RideSample
import de.uhi.enia.ridesafe.rides.recording.RideSamples
import kotlinx.serialization.json.Json
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Replays real recorded rides through the production detector and prints what it finds — the
 * calibration loop the config's own docs call for: reading thresholds off real rides beats
 * guessing at them.
 *
 * Skipped unless `-Dridesafe.replay.dir=<dir>` points at a directory of `ride_<startNanos>.ndjson`
 * (or `.gz`) files — pull them from a device backup's `f/rides/`. Run with:
 * `./gradlew :app:testDebugUnitTest --tests '*RealRideReplayTest' -Dridesafe.replay.dir=...`
 * (add the property to gradle.properties as `systemProp.ridesafe.replay.dir=` if it doesn't reach
 * the test JVM). Deliberately asserts nothing: real rides have no ground truth a test could hold,
 * only a driver's account to compare the printout against.
 */
class RealRideReplayTest {
    private val json =
        Json {
            classDiscriminator = "ty"
            ignoreUnknownKeys = true
        }

    @Test
    fun replayRecordedRides() {
        val dir = System.getProperty("ridesafe.replay.dir")?.let(::File)
        Assume.assumeTrue("no -Dridesafe.replay.dir given; skipping", dir != null && dir.isDirectory)

        val files =
            dir!!
                .listFiles { f -> f.name.startsWith("ride_") && ".ndjson" in f.name }
                .orEmpty()
                .sortedBy { it.name }
        for (file in files) {
            val startNanos = file.name.removePrefix("ride_").substringBefore('.').toLongOrNull() ?: continue
            val samples = read(file)
            val events = detectRideEvents(samples, startNanos)
            println(
                "${file.name}: ${samples.locations.size} fixes, ${samples.accel.size} accel -> ${events.size} events",
            )
            for (e in events) {
                println(
                    "  %-12s t=%7.1fs dur=%5d ms peak=%.3f g jerk=%.2f g/s at %.0f km/h"
                        .format(e.type, e.startOffsetMs / 1000.0, e.durationMs, e.peakG, e.peakJerkGPerS, e.speedMps * 3.6),
                )
            }
        }
    }

    private fun read(file: File): RideSamples {
        val locations = ArrayList<LocationSample>()
        val accel = ArrayList<MotionSample>()
        val gyro = ArrayList<MotionSample>()
        val rotation = ArrayList<MotionSample>()
        val input = FileInputStream(file).let { if (file.name.endsWith(".gz")) GZIPInputStream(it) else it }
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                when (val sample = runCatching { json.decodeFromString<RideSample>(line) }.getOrNull()) {
                    is LocationSample -> locations.add(sample)
                    is MotionSample ->
                        when (sample.sensor) {
                            MotionSensor.ACCEL -> accel.add(sample)
                            MotionSensor.GYRO -> gyro.add(sample)
                            MotionSensor.ROTATION -> rotation.add(sample)
                        }
                    null -> {}
                }
            }
        }
        // The file interleaves streams out of order (sensor FIFO batching); each stream must be
        // monotonic for the detector's merge, exactly as readRideSamples guarantees in production.
        locations.sortBy { it.t }
        accel.sortBy { it.t }
        gyro.sortBy { it.t }
        rotation.sortBy { it.t }
        return RideSamples(locations, accel, gyro, rotation)
    }
}
