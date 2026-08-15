package de.uhi.enia.ridesafe.tracking

import de.uhi.enia.ridesafe.data.DriveEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Covers the two things that are easy to get silently wrong and hard to debug from a real ride: the
 * device→world orientation convention, and whether gravity leaks into the horizontal plane on a
 * slope. Both use hand-computed device-frame readings rather than a helper that would just invert
 * the production math and pass regardless. The rest covers the event state machine.
 */
class DriveEventsTest {
    private companion object {
        const val GRAVITY = 9.80665
        const val MOTION_HZ = 50
        const val METERS_PER_DEGREE_LON = 71_470.0 // at latitude 50°, matching EARTH_RADIUS_M

        /** Phone flat, face up, top edge pointing north — Android's identity rotation vector. */
        val LEVEL = floatArrayOf(0f, 0f, 0f, 1f)

        /** Yawed 90° about world-up: the device's +x axis now points north, +y points west. */
        val YAWED_90 = floatArrayOf(0f, 0f, sin(Math.PI / 4).toFloat(), cos(Math.PI / 4).toFloat())
    }

    /**
     * Builds a synthetic eastbound ride: 1 Hz fixes stepping east at [speedMps], 50 Hz motion at a
     * fixed device orientation. [deviceAccelAt] returns the raw device-frame reading (gravity
     * included, as a real accelerometer reports) for a time in seconds.
     */
    private fun ride(
        seconds: Double,
        quaternion: FloatArray = LEVEL,
        speedMps: Double = 20.0,
        gyroRadPerSec: Double = 0.0,
        deviceAccelAt: (Double) -> Triple<Double, Double, Double>,
    ): RideSamples {
        val locations =
            (0..seconds.toInt()).map { second ->
                LocationSample(
                    t = second * 1_000_000_000L,
                    lat = 50.0,
                    lon = 8.0 + (second * speedMps) / METERS_PER_DEGREE_LON,
                    alt = 0.0,
                    speed = speedMps.toFloat(),
                    bearing = 90f, // east; overwritten by the Kalman pass inside the detector
                    accuracy = 5f,
                )
            }
        val accel = ArrayList<MotionSample>()
        val gyro = ArrayList<MotionSample>()
        val rotation = ArrayList<MotionSample>()
        val steps = (seconds * MOTION_HZ).toInt()
        for (step in 0..steps) {
            val nanos = step * (1_000_000_000L / MOTION_HZ)
            val (x, y, z) = deviceAccelAt(step.toDouble() / MOTION_HZ)
            accel.add(MotionSample(nanos, MotionSensor.ACCEL, x.toFloat(), y.toFloat(), z.toFloat()))
            gyro.add(MotionSample(nanos, MotionSensor.GYRO, gyroRadPerSec.toFloat(), 0f, 0f))
            rotation.add(
                MotionSample(nanos, MotionSensor.ROTATION, quaternion[0], quaternion[1], quaternion[2], quaternion[3]),
            )
        }
        return RideSamples(locations, accel, gyro, rotation)
    }

    /**
     * A phone yawed 90° in the mount, in a car braking eastward at 0.35 g. Hand-computed: with the
     * device's +x pointing north and +y pointing west, a world reading of (east −3.43, north 0,
     * up 9.81) lands on the device axes as (0, +3.43, 9.81). The detector must undo that and report
     * braking — not cornering, which is what a wrong rotation convention would produce.
     */
    @Test
    fun resolvesBrakingThroughAnArbitraryPhoneOrientation() {
        val events =
            detectDriveEvents(
                ride(seconds = 20.0, quaternion = YAWED_90) { t ->
                    if (t >= 10.0 && t <= 12.0) {
                        Triple(0.0, 3.43, GRAVITY)
                    } else {
                        Triple(0.0, 0.0, GRAVITY)
                    }
                },
                rideStartElapsedNanos = 0L,
            )

        assertEquals("expected exactly one event, got $events", 1, events.size)
        assertEquals(DriveEventType.BRAKING, events[0].type)
        assertTrue("peak should be ~0.35 g, was ${events[0].peakG}", events[0].peakG > 0.33)
    }

    /**
     * The slope case: a car sitting on a 10% downgrade, coasting, nothing harsh happening. Gravity
     * now falls partly along the car's own axes, which is exactly what a device-frame detector would
     * misread as sustained acceleration. Rotating into the world frame and dropping the vertical
     * must leave nothing behind.
     */
    @Test
    fun gravityOnASlopeDoesNotLeakIntoHorizontal() {
        // Deliberately steeper than any real road (27% grade). A correct implementation cancels
        // gravity exactly at any tilt, so the angle costs nothing — but it means a sign or convention
        // error injects ~0.5 g, far above any plausible threshold, instead of hiding just under one.
        val pitch = Math.toRadians(15.0)
        val tilted = floatArrayOf(sin(pitch / 2).toFloat(), 0f, 0f, cos(pitch / 2).toFloat())
        // Device-frame reading of pure gravity at that tilt: Rᵀ·(0,0,g), written out directly. The
        // rotation is about world-east, so the device's +y (tilted skyward) reads +g·sin θ — a minus
        // here silently injects ~0.2 g of real lateral acceleration and the test stops testing gravity.
        val resting = Triple(0.0, GRAVITY * sin(pitch), GRAVITY * cos(pitch))

        val events = detectDriveEvents(ride(seconds = 20.0, quaternion = tilted) { resting }, 0L)

        assertTrue("a coasting car on a slope must produce no events, got $events", events.isEmpty())
    }

    /** A pure vertical shock — the pothole case — has no horizontal component and must be invisible. */
    @Test
    fun verticalShockIsIgnored() {
        val events =
            detectDriveEvents(
                ride(seconds = 20.0) { t ->
                    if (t >= 10.0 && t <= 10.4) Triple(0.0, 0.0, GRAVITY * 3) else Triple(0.0, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        assertTrue("vertical-only shock must not register, got $events", events.isEmpty())
    }

    /**
     * The event-splitting guard: one continuous 3-second brake, with a brief dip in the middle where
     * the driver eased off. That is one brake, not two, and its duration is a scoring input so it
     * has to come out close to the real 3 seconds.
     */
    @Test
    fun oneSustainedBrakeWithADipStaysOneEvent() {
        val events =
            detectDriveEvents(
                ride(seconds = 20.0) { t ->
                    val decel =
                        when {
                            t >= 10.5 && t < 10.7 -> 0.0 // driver lifts off entirely: decays well under exitG
                            t >= 10.0 && t <= 13.0 -> 3.43 // 0.35 g
                            else -> 0.0
                        }
                    Triple(-decel, 0.0, GRAVITY) // device is level, so -x is west = braking eastbound
                },
                rideStartElapsedNanos = 0L,
            )

        assertEquals("the dip must not split the brake, got $events", 1, events.size)
        assertEquals(DriveEventType.BRAKING, events[0].type)
        assertTrue("duration should be ~3000 ms, was ${events[0].durationMs}", events[0].durationMs in 2900..3200)
        assertTrue("should start ~10 s in, was ${events[0].startOffsetMs}", events[0].startOffsetMs in 10_000..10_200)
    }

    /** A jolt shorter than minDurationMs is road noise, not a driving event. */
    @Test
    fun briefJoltIsNotAnEvent() {
        val events =
            detectDriveEvents(
                ride(seconds = 20.0) { t ->
                    if (t >= 10.0 && t <= 10.06) Triple(-9.8, 0.0, GRAVITY) else Triple(0.0, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        assertTrue("a 60 ms jolt must be rejected, got $events", events.isEmpty())
    }

    /** Hard braking while crawling is a parking maneuver, and is gated out by speed. */
    @Test
    fun harshBrakingBelowTheSpeedGateIsIgnored() {
        val events =
            detectDriveEvents(
                ride(seconds = 20.0, speedMps = 2.0) { t ->
                    if (t >= 10.0 && t <= 12.0) Triple(-3.43, 0.0, GRAVITY) else Triple(0.0, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        assertTrue("below the speed gate nothing should register, got $events", events.isEmpty())
    }

    /** Spinning the phone in your hand produces huge readings; the gyro gate must discard them. */
    @Test
    fun phoneHandlingIsRejected() {
        val events =
            detectDriveEvents(
                ride(seconds = 20.0, gyroRadPerSec = 6.0) { t ->
                    if (t >= 10.0 && t <= 12.0) Triple(-3.43, 0.0, GRAVITY) else Triple(0.0, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        assertTrue("handling the phone must not score, got $events", events.isEmpty())
    }

    /** A missing sensor means no score, never a guessed one. */
    @Test
    fun missingRotationVectorYieldsNothing() {
        val full = ride(seconds = 20.0) { Triple(-3.43, 0.0, GRAVITY) }

        assertTrue(detectDriveEvents(full.copy(rotation = emptyList()), 0L).isEmpty())
        assertTrue(detectDriveEvents(full.copy(accel = emptyList()), 0L).isEmpty())
        assertTrue(detectDriveEvents(full.copy(locations = emptyList()), 0L).isEmpty())
    }
}
