package de.uhi.enia.ridesafe.rides.recording

import android.content.Context
import android.util.Log
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.util.haversineMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
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
 * @param onProgress receives how far through the file the read is, as 0..1 of the compressed bytes
 * consumed. Called only when the fraction has moved by at least [PROGRESS_STEP], so a short ride
 * reports as often as a long one and neither floods its listener. Compressed bytes rather than
 * samples because the total sample count isn't known without reading the file first.
 * @param onSample receives every parsed sample in ascending timestamp order.
 */
fun forEachSampleInTimeOrder(
    file: File,
    reorderWindowNanos: Long = 15_000_000_000L,
    onProgress: ((Float) -> Unit)? = null,
    onSample: (RideSample) -> Unit,
) {
    // Explicit comparator rather than compareBy: the latter boxes the Long on every comparison, and
    // this runs tens of millions of times on a long ride.
    val pending = PriorityQueue(1024) { a: RideSample, b: RideSample -> a.t.compareTo(b.t) }
    var newest = Long.MIN_VALUE
    val totalBytes = file.length()
    var reported = 0f
    try {
        // Counted underneath the gzip, so progress tracks the file being consumed rather than the
        // decompressed volume, which isn't known up front.
        val counting = CountingInputStream(FileInputStream(file))
        GZIPInputStream(counting).bufferedReader().use { reader ->
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
                if (onProgress != null && totalBytes > 0) {
                    val fraction = (counting.bytesRead.toFloat() / totalBytes).coerceAtMost(1f)
                    if (fraction - reported >= PROGRESS_STEP) {
                        reported = fraction
                        onProgress(fraction)
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
    onProgress?.invoke(1f)
}

/** Smallest change in read position worth reporting — 1%, so any ride reports about 100 times. */
private const val PROGRESS_STEP = 0.01f

/** Counts bytes pulled through it, so a read's position in the file can be reported as it happens. */
private class CountingInputStream(
    private val delegate: InputStream,
) : InputStream() {
    var bytesRead = 0L
        private set

    override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = delegate.read(b, off, len).also { if (it > 0) bytesRead += it }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}

/** Total path length in meters, summed great-circle over consecutive fixes (ANL-02 primitive). */
fun trackDistanceMeters(locations: List<LocationSample>): Double =
    locations.zipWithNext().sumOf { (a, b) -> haversineMeters(a.lat, a.lon, b.lat, b.lon) }

/**
 * Delete a logged ride and the sample file that belongs to it — what the car screen's "delete this
 * ride" does (LOG-05). The driving events and analysis state rows go with it: both cascade on the
 * ride row.
 */
suspend fun deleteRide(
    appContext: Context,
    rideId: Long,
) {
    val dao = RidesafeDatabase.getInstance(appContext).rideDao()
    dao.byId(rideId)?.let { File(ridesDir(appContext), it.sampleFile).delete() }
    dao.deleteById(rideId)
}
