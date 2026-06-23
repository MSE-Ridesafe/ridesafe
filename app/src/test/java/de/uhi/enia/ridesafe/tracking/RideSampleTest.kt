package de.uhi.enia.ridesafe.tracking

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the non-trivial pure logic: the [summarize] math and the on-disk sample round-trip. */
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
    ) = LocationSample(t = tNanos, lat = lat, lon = lon, alt = 0.0, speed = speed, bearing = 0f, accuracy = 0f)

    @Test
    fun emptyOrSinglePointHasNoDistance() {
        assertEquals(RideSummary(0.0, 0.0, 0.0), summarize(emptyList()))
        assertEquals(0.0, summarize(listOf(loc(0, 50.0, 8.0))).distanceMeters, 0.0)
    }

    @Test
    fun haversineMatchesKnownDistance() {
        // ~1 degree of latitude is ~111.2 km; haversine should be within ~0.5%.
        val d = haversineMeters(50.0, 8.0, 51.0, 8.0)
        assertEquals(111_195.0, d, 600.0)
    }

    @Test
    fun summaryAccumulatesDistanceAndSpeeds() {
        // Two 1°-lat hops over 200s => ~222 km, avg ~1111 m/s; max = fastest reported fix.
        val samples =
            listOf(
                loc(0, 50.0, 8.0, speed = 10f),
                loc(100_000_000_000, 51.0, 8.0, speed = 30f),
                loc(200_000_000_000, 52.0, 8.0, speed = 20f),
            )
        val s = summarize(samples)
        assertEquals(222_390.0, s.distanceMeters, 1_200.0)
        assertEquals(s.distanceMeters / 200.0, s.avgSpeedMps, 0.001)
        assertEquals(30.0, s.maxSpeedMps, 0.0)
    }

    @Test
    fun samplesRoundTripThroughJsonPolymorphically() {
        val original: List<RideSample> =
            listOf(
                loc(1, 50.1, 8.2, speed = 12.5f),
                MotionSample(t = 2, sensor = MotionSensor.ACCEL, x = 0.1f, y = 9.8f, z = 0.2f),
                MotionSample(t = 3, sensor = MotionSensor.ROTATION, x = 0.1f, y = 0.2f, z = 0.3f, w = 0.9f),
            )
        val lines = original.map { json.encodeToString(it) }
        val decoded = lines.map { json.decodeFromString<RideSample>(it) }
        assertEquals(original, decoded)
        // Accelerometer's null w is omitted from the wire form (encodeDefaults = false).
        assertTrue(lines[1].contains("\"ty\":\"mot\""))
        assertTrue(!lines[1].contains("\"w\""))
    }
}
