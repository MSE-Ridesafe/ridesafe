package de.uhi.enia.ridesafe.rides.processing

import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import de.uhi.enia.ridesafe.rides.recording.RideSample
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Covers the two pieces of the pipeline that decide real behaviour: which steps a ride needs, and
 * the sample stream those steps see. The steps themselves are thin wrappers over algorithms already
 * covered by [RideProcessingTest] and RideEventsTest.
 */
class RideAnalysisPipelineTest {
    private val json =
        Json {
            classDiscriminator = "ty"
            encodeDefaults = false
        }

    /** A ride written the way the recorder writes one: GPS live, motion flushed in 5-second batches. */
    private fun rideFile(
        seconds: Int,
        accuracy: Float = 5f,
    ): File {
        val file = File.createTempFile("ride", ".ndjson.gz").apply { deleteOnExit() }
        val lines = ArrayList<RideSample>()
        var batchStart = 0
        for (second in 0 until seconds) {
            // ~20 m/s due east, accurate enough for the filter to keep every fix.
            lines.add(LocationSample(second * 1_000_000_000L, 50.0, 8.0 + second * 0.00028, 0.0, 20f, 90f, accuracy))
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

    private fun collecting(into: MutableList<RideSample>) = SampleSink { into.add(it) }

    /**
     * The load-bearing claim of the whole change: a later pass reusing the cached fixes sees exactly
     * what it would have seen from a second Kalman run. If this drifts, detection silently changes
     * without any version bump saying so.
     */
    @Test
    fun `second pass over cached fixes matches re-filtering the file`() =
        runBlocking {
            val file = rideFile(seconds = 30)

            val firstPass = mutableListOf<RideSample>()
            val fixes = streamSamples(1L, file, listOf(collecting(firstPass)))!!

            val reused = mutableListOf<RideSample>()
            streamSamples(1L, file, listOf(collecting(reused)), fixes)

            // The pass that filters also emits the raw motion; the pass that reuses emits the same
            // motion with the same fixes spliced back in at the same points.
            assertEquals(firstPass, reused)
            assertTrue("the filter should have kept fixes to reuse", fixes.isNotEmpty())
            assertEquals(fixes.map { it.fix }, firstPass.filterIsInstance<LocationSample>())
        }

    /** Fixes already in hand and nobody to feed: the pass is a no-op and must not re-read the file. */
    @Test
    fun `a pass with no sinks and cached fixes reads nothing`() =
        runBlocking {
            val fixes = listOf(ReleasedFix(0L, LocationSample(0L, 50.0, 8.0, 0.0, 20f, 90f, 5f)))
            assertEquals(fixes, streamSamples(1L, File("does-not-exist"), emptyList(), fixes))
        }

    /**
     * With no fixes cached, a sinkless pass still has to filter: a stage that reads the track
     * without a per-sample callback — the endpoint correction — would otherwise be handed an empty
     * track and quietly conclude the ride never moved.
     */
    @Test
    fun `a sinkless pass still filters when no fixes are cached`() =
        runBlocking {
            val fixes = streamSamples(1L, rideFile(seconds = 30), emptyList())
            assertTrue("the track must be filtered for a stage that has no sink", !fixes.isNullOrEmpty())
        }

    /**
     * A ride whose fixes are all rejected filters to an empty list, which must not read as "not
     * filtered yet" — otherwise every later pass re-reads and re-filters the whole file.
     */
    @Test
    fun `a track that filters to nothing is still only filtered once`() =
        runBlocking {
            // Every fix is worse than the filter's accuracy ceiling, so nothing survives — the
            // real shape of rides 31/63, where the fused provider reported hundreds of meters.
            val file = rideFile(seconds = 30, accuracy = 500f)
            val first = streamSamples(1L, file, listOf(collecting(mutableListOf())))
            assertEquals(emptyList<ReleasedFix>(), first)

            val reused = mutableListOf<RideSample>()
            streamSamples(1L, file, listOf(collecting(reused)), first)
            assertTrue("the motion stream should still flow", reused.isNotEmpty())
            assertTrue("no fix may appear from an empty track", reused.none { it is LocationSample })
        }

    /**
     * The endpoint correction's whole judgement call: the filter nudges every fix, so a small shift
     * is smoothing and must not cost a Geocoder call, while the fused-provider glitches this exists
     * to catch land far away.
     */
    @Test
    fun `only a real endpoint move counts as a correction`() {
        val lat = 52.15
        val lon = 9.95
        // ~11 m north — the filter smoothing a fix, not a mistake.
        assertFalse(movedFar(lat, lon, lat + 0.0001, lon))
        // ~333 m north — a fix the ride never visited.
        assertTrue(movedFar(lat, lon, lat + 0.003, lon))
        // Nothing recorded to compare against is not evidence of a mistake.
        assertFalse(movedFar(null, null, lat, lon))
        assertFalse(movedFar(lat, null, lat + 0.003, lon))
    }

    private class FakeStage(
        override val id: String,
        override val version: Int,
        override val dependsOn: List<String> = emptyList(),
        override val needsSamples: Boolean = true,
        override val restorable: Boolean = false,
    ) : RideStage {
        override suspend fun finish(ctx: RideAnalysisContext) = Unit
    }

    private val a = FakeStage("a", version = 1, restorable = true)
    private val b = FakeStage("b", version = 1, dependsOn = listOf("a"), restorable = true)
    private val c = FakeStage("c", version = 1, dependsOn = listOf("b"), needsSamples = false, restorable = true)
    private val stages = listOf(a, b, c)

    private fun idsOf(stages: List<RideStage>) = stages.map { it.id }

    @Test
    fun `an unanalyzed ride runs everything`() {
        val plan = planStages(stages, emptyMap())
        assertEquals(listOf("a", "b", "c"), idsOf(plan.run))
        assertEquals(emptyList<String>(), idsOf(plan.load))
    }

    @Test
    fun `a stale step drags everything derived from it along`() {
        val plan = planStages(stages, mapOf("a" to 0, "b" to 1, "c" to 1))
        assertEquals(listOf("a", "b", "c"), idsOf(plan.run))
    }

    /**
     * The case per-step versions exist for: re-deriving the last step alone. Its inputs are current,
     * so they are restored rather than recomputed, and nothing in the plan wants the sample file —
     * which is what keeps a scoring re-tune off the disk.
     */
    @Test
    fun `re-deriving a leaf step restores its inputs instead of re-running them`() {
        val plan = planStages(stages, mapOf("a" to 1, "b" to 1, "c" to 0))
        assertEquals(listOf("c"), idsOf(plan.run))
        assertEquals(listOf("b"), idsOf(plan.load))
        assertTrue("a pure-derivation step must not schedule a file pass", plan.run.none { it.needsSamples })
    }

    /** A dependency with nothing stored to restore has to be re-derived, current version or not. */
    @Test
    fun `an unrestorable dependency is re-run rather than loaded`() {
        val unrestorable = FakeStage("b", version = 1, dependsOn = listOf("a"))
        val plan = planStages(listOf(a, unrestorable, c), mapOf("a" to 1, "b" to 1, "c" to 0))
        assertEquals(listOf("b", "c"), idsOf(plan.run))
        // b is derived again, so a — restorable and current — is restored to feed it.
        assertEquals(listOf("a"), idsOf(plan.load))
    }
}
