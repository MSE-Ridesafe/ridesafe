package de.uhi.enia.ridesafe.rides.recording

import de.uhi.enia.ridesafe.util.haversineMeters
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the non-trivial pure logic: the [RideStats] endpoints/speed and the on-disk round-trip. */
class RideSampleTest {
    private val json =
        Json {
            classDiscriminator = "ty"
            encodeDefaults = false
        }

    private fun loc(
        tNanos: Long,
        lat: Double,
        lon: Double,
        speed: Float = 0f,
    ) = LocationSample(
        t = tNanos,
        lat = lat,
        lon = lon,
        alt = 0.0,
        speed = speed,
        bearing = 0f,
        accuracy = 0f,
    )

    @Test
    fun emptyStreamHasNoEndpoints() {
        val stats = rideStatsOf(emptyList())
        assertNull(stats.startFix)
        assertNull(stats.endFix)
        assertEquals(0.0, stats.maxSpeedMps, 0.0)
    }

    @Test
    fun haversineMatchesKnownDistance() {
        // ~1 degree of latitude is ~111.2 km; haversine should be within ~0.5%.
        val d = haversineMeters(50.0, 8.0, 51.0, 8.0)
        assertEquals(111_195.0, d, 600.0)
    }

    @Test
    fun statsCaptureFirstLastFixAndMaxSpeed() {
        val stats =
            rideStatsOf(
                listOf(
                    loc(0, 50.0, 8.0, speed = 10f),
                    loc(100, 51.0, 8.5, speed = 30f),
                    loc(200, 52.0, 9.0, speed = 20f),
                ),
            )
        assertEquals(50.0, stats.startFix!!.lat, 0.0)
        assertEquals(8.0, stats.startFix!!.lon, 0.0)
        assertEquals(52.0, stats.endFix!!.lat, 0.0)
        assertEquals(9.0, stats.endFix!!.lon, 0.0)
        assertEquals(30.0, stats.maxSpeedMps, 0.0) // fastest reported fix
    }

    @Test
    fun trackDistanceIsZeroForEmptyOrSingleFix() {
        assertEquals(0.0, trackDistanceMeters(emptyList()), 0.0)
        assertEquals(0.0, trackDistanceMeters(listOf(loc(0, 50.0, 8.0))), 0.0)
    }

    @Test
    fun trackDistanceSumsConsecutiveLegs() {
        // Two ~1-degree-latitude legs (~111.2 km each) should sum to ~222 km.
        val d = trackDistanceMeters(listOf(loc(0, 50.0, 8.0), loc(1, 51.0, 8.0), loc(2, 52.0, 8.0)))
        assertEquals(222_390.0, d, 1_200.0)
    }

    @Test
    fun samplesRoundTripThroughJsonPolymorphically() {
        val original: List<RideSample> =
            listOf(
                loc(1, 50.1, 8.2, speed = 12.5f),
                MotionSample(t = 2, sensor = MotionSensor.ACCEL, x = 0.1f, y = 9.8f, z = 0.2f),
                MotionSample(
                    t = 3,
                    sensor = MotionSensor.ROTATION,
                    x = 0.1f,
                    y = 0.2f,
                    z = 0.3f,
                    w = 0.9f,
                ),
            )
        val lines = original.map { json.encodeToString(it) }
        val decoded = lines.map { json.decodeFromString<RideSample>(it) }
        assertEquals(original, decoded)
        // Accelerometer's null w is omitted from the wire form (encodeDefaults = false).
        assertTrue(lines[1].contains("\"ty\":\"mot\""))
        assertTrue(!lines[1].contains("\"w\""))
    }

    private fun motion(tNanos: Long) = MotionSample(t = tNanos, sensor = MotionSensor.ACCEL, x = 0f, y = 0f, z = 0f)

    /** Feeds [samples] through a [RideTail], collecting what it lets through. */
    private fun writtenBy(
        tail: RideTail,
        samples: List<RideSample>,
    ): List<RideSample> {
        val out = ArrayList<RideSample>()
        samples.forEach { tail.accept(it, out::add) }
        tail.drain(out::add)
        return out
    }

    @Test
    fun tailPassesEverythingThroughBeforeTheRideEnds() {
        val tail = RideTail()
        val samples = listOf(loc(1, 50.0, 8.0), motion(2), loc(3, 50.1, 8.0))
        assertEquals(samples, writtenBy(tail, samples))
    }

    @Test
    fun tailDropsWhatWasRecordedAfterTheMark() {
        val tail = RideTail()
        tail.begin(markNanos = 100)
        // A batched motion sample from before the mark lands late — it still belongs to the ride.
        val written = writtenBy(tail, listOf(loc(150, 50.0, 8.0), motion(90), loc(200, 50.1, 8.0)))
        assertEquals(listOf(motion(90)), written)
    }

    @Test
    fun tailReleasesHeldSamplesInOrderWhenTheCarReconnects() {
        val tail = RideTail()
        val out = ArrayList<RideSample>()
        val write: (RideSample) -> Unit = out::add
        tail.begin(markNanos = 100)
        listOf(loc(150, 50.0, 8.0), loc(160, 50.1, 8.0)).forEach { tail.accept(it, write) }
        assertTrue(out.isEmpty()) // held while the grace is open
        tail.rejoin()
        tail.accept(loc(170, 50.2, 8.0), write)
        tail.drain(write)
        assertEquals(listOf(loc(150, 50.0, 8.0), loc(160, 50.1, 8.0), loc(170, 50.2, 8.0)), out)
    }
}
