package de.uhi.enia.ridesafe.tracking

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
    ) = LocationSample(t = tNanos, lat = lat, lon = lon, alt = 0.0, speed = speed, bearing = 0f, accuracy = 0f)

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
