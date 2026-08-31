package de.uhi.enia.ridesafe.data.file

import de.uhi.enia.ridesafe.data.file.LocationSample
import de.uhi.enia.ridesafe.data.file.MotionSample
import de.uhi.enia.ridesafe.data.file.MotionSensor
import de.uhi.enia.ridesafe.data.file.RideSample
import de.uhi.enia.ridesafe.data.file.forEachSampleInTimeOrder
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Covers the streaming reader that replaced materialising a ride. Its whole job is undoing the
 * file's interleaving — the sensor FIFO batches motion and flushes it late, so a motion sample is
 * written well after GPS fixes that are older than it — while holding only a bounded window.
 */
class TimeOrderedReadTest {
    private val json =
        Json {
            classDiscriminator = "ty"
            encodeDefaults = false
        }

    /**
     * Writes a ride the way the recorder does: GPS is written live, motion arrives in 5-second
     * batches, so the file is badly out of order even though each sensor's own stream is monotonic.
     */
    private fun batchedRideFile(seconds: Int): File {
        val file = File.createTempFile("ride", ".ndjson.gz").apply { deleteOnExit() }
        val lines = ArrayList<RideSample>()
        var batchStart = 0
        for (second in 0 until seconds) {
            lines.add(LocationSample(second * 1_000_000_000L, 50.0, 8.0, 0.0, 20f, 90f, 5f))
            // Every fifth second the FIFO flushes everything sampled since the last flush.
            if ((second + 1) % 5 == 0) {
                for (step in batchStart * 50 until (second + 1) * 50) {
                    val t = step * 20_000_000L
                    lines.add(MotionSample(t, MotionSensor.ACCEL, 0f, 0f, 9.8f))
                    lines.add(MotionSample(t, MotionSensor.ROTATION, 0f, 0f, 0f, 1f))
                }
                batchStart = second + 1
            }
        }
        GZIPOutputStream(FileOutputStream(file)).bufferedWriter().use { w ->
            lines.forEach {
                w.write(json.encodeToString<RideSample>(it))
                w.newLine()
            }
        }
        return file
    }

    @Test
    fun deliversBatchedFileInTimestampOrder() {
        val file = batchedRideFile(seconds = 60)

        // The file itself is badly out of order — otherwise this test proves nothing.
        val asWritten = ArrayList<Long>()
        readRideSamplesRaw(file, asWritten)
        Assert.assertTrue(
            "the fixture must actually be out of order",
            (1 until asWritten.size).any { asWritten[it] < asWritten[it - 1] },
        )

        val delivered = ArrayList<Long>()
        forEachSampleInTimeOrder(file) { delivered.add(it.t) }

        Assert.assertEquals("every sample must still be delivered", asWritten.size, delivered.size)
        val firstBreak = (1 until delivered.size).firstOrNull { delivered[it] < delivered[it - 1] }
        Assert.assertEquals(
            "timestamps must come out monotonic, broke at $firstBreak",
            null,
            firstBreak,
        )
    }

    /** A window shorter than the batch latency cannot reorder fully — the guard against a silent regression. */
    @Test
    fun deliversEverythingEvenWhenTheWindowIsTooShort() {
        val file = batchedRideFile(seconds = 30)
        val delivered = ArrayList<Long>()
        forEachSampleInTimeOrder(file, reorderWindowNanos = 1_000_000) { delivered.add(it.t) }

        val asWritten = ArrayList<Long>()
        readRideSamplesRaw(file, asWritten)
        Assert.assertEquals(
            "no sample may be dropped regardless of window size",
            asWritten.size,
            delivered.size,
        )
    }

    /**
     * Cancellation must unwind, not be mistaken for a corrupt file. The reader wraps its whole loop
     * in a catch that used to swallow every exception, which meant a cancelled analysis kept reading
     * millions of samples nobody was waiting for.
     */
    @Test
    fun cancellationPropagatesInsteadOfBeingSwallowed() {
        val file = batchedRideFile(seconds = 30)
        var seen = 0
        val thrown =
            runCatching {
                forEachSampleInTimeOrder(file) {
                    if (++seen == 50) throw kotlin.coroutines.cancellation.CancellationException("cancelled")
                }
            }.exceptionOrNull()

        Assert.assertTrue(
            "cancellation must escape the reader, got $thrown",
            thrown is kotlin.coroutines.cancellation.CancellationException,
        )
        Assert.assertEquals("and must stop the read where it was cancelled", 50, seen)
    }

    private fun readRideSamplesRaw(
        file: File,
        into: MutableList<Long>,
    ) {
        GZIPInputStream(FileInputStream(file)).bufferedReader().use { r ->
            r.forEachLine { line -> into.add(json.decodeFromString<RideSample>(line).t) }
        }
    }
}
