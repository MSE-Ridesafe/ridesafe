package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.data.RideDynamics
import de.uhi.enia.ridesafe.data.RideEventType
import de.uhi.enia.ridesafe.rides.processing.kalmanFilterLocations
import de.uhi.enia.ridesafe.rides.processing.score.scoreRide
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import de.uhi.enia.ridesafe.rides.recording.RideSamples
import org.junit.Assert
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Covers the two things that are easy to get silently wrong and hard to debug from a real ride: the
 * device→world orientation convention, and whether gravity leaks into the horizontal plane on a
 * slope. Both use hand-computed device-frame readings rather than a helper that would just invert
 * the production math and pass regardless. The rest covers the event state machine, and the last few
 * cover the dynamics profile the same pass accumulates for scoring (ANL-01).
 */
class RideEventsTest {
    private companion object {
        const val GRAVITY = 9.80665
        const val MOTION_HZ = 50
        const val METERS_PER_DEGREE_LON = 71_470.0 // at latitude 50°, matching EARTH_RADIUS_M
        const val METERS_PER_DEGREE_LAT = 111_195.0

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
        travelBearingAt: (Double) -> Double = { 90.0 }, // due east unless a test says otherwise
        yawRateAt: (Double) -> Double = { 0.0 }, // rad/s about world up; device z for a level phone
        // Overrides the constant quaternion per sample — how a test models a rotation vector whose
        // reading moves (or errs) over time while the device accel stays in the true device axes.
        quaternionAt: ((Double) -> FloatArray)? = null,
        // Doppler speed over time. Longitudinal maneuvers must move this consistently with their
        // force — the detector discards a braking or acceleration event the car's speed belies.
        speedAt: ((Double) -> Double)? = null,
        deviceAccelAt: (Double) -> Triple<Double, Double, Double>,
    ): RideSamples {
        // Positions are integrated along the travel bearing rather than assumed straight, so a test
        // can send the GPS somewhere the car isn't actually going.
        val locations = ArrayList<LocationSample>()
        var lat = 50.0
        var lon = 8.0
        for (second in 0..seconds.toInt()) {
            val v = speedAt?.invoke(second.toDouble()) ?: speedMps
            locations.add(
                LocationSample(
                    t = second * 1_000_000_000L,
                    lat = lat,
                    lon = lon,
                    alt = 0.0,
                    speed = v.toFloat(),
                    bearing = 90f, // overwritten by the Kalman pass inside the detector
                    accuracy = 5f,
                ),
            )
            val heading = Math.toRadians(travelBearingAt(second.toDouble()))
            lat += (v * cos(heading)) / METERS_PER_DEGREE_LAT
            lon += (v * sin(heading)) / METERS_PER_DEGREE_LON
        }
        val accel = ArrayList<MotionSample>()
        val gyro = ArrayList<MotionSample>()
        val rotation = ArrayList<MotionSample>()
        val steps = (seconds * MOTION_HZ).toInt()
        for (step in 0..steps) {
            val nanos = step * (1_000_000_000L / MOTION_HZ)
            val (x, y, z) = deviceAccelAt(step.toDouble() / MOTION_HZ)
            accel.add(
                MotionSample(
                    nanos,
                    MotionSensor.ACCEL,
                    x.toFloat(),
                    y.toFloat(),
                    z.toFloat(),
                ),
            )
            gyro.add(
                MotionSample(
                    nanos,
                    MotionSensor.GYRO,
                    gyroRadPerSec.toFloat(),
                    0f,
                    yawRateAt(step.toDouble() / MOTION_HZ).toFloat(),
                ),
            )
            val q = quaternionAt?.invoke(step.toDouble() / MOTION_HZ) ?: quaternion
            rotation.add(
                MotionSample(
                    nanos,
                    MotionSensor.ROTATION,
                    q[0],
                    q[1],
                    q[2],
                    q[3],
                ),
            )
        }
        return RideSamples(locations, accel, gyro, rotation)
    }

    /**
     * Doppler speed dropping [decelMps2] × the braking window, flat on either side — the speed
     * trace every braking synthetic needs so its event survives the Doppler agreement check.
     */
    private fun speedDrop(
        initial: Double,
        decelMps2: Double,
        from: Double,
        to: Double,
    ): (Double) -> Double =
        { t ->
            when {
                t < from -> initial
                t < to -> initial - decelMps2 * (t - from)
                else -> initial - decelMps2 * (to - from)
            }
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
            detectRideEvents(
                ride(seconds = 20.0, quaternion = YAWED_90, speedAt = speedDrop(20.0, 3.43, 10.0, 12.0)) { t ->
                    if (t >= 10.0 && t <= 12.0) {
                        Triple(0.0, 3.43, GRAVITY)
                    } else {
                        Triple(0.0, 0.0, GRAVITY)
                    }
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertEquals("expected exactly one event, got $events", 1, events.size)
        Assert.assertEquals(RideEventType.BRAKING, events[0].type)
        Assert.assertTrue("peak should be ~0.35 g, was ${events[0].peakG}", events[0].peakG > 0.33)
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

        val events = detectRideEvents(ride(seconds = 20.0, quaternion = tilted) { resting }, 0L)

        Assert.assertTrue(
            "a coasting car on a slope must produce no events, got $events",
            events.isEmpty(),
        )
    }

    /** A pure vertical shock — the pothole case — has no horizontal component and must be invisible. */
    @Test
    fun verticalShockIsIgnored() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0) { t ->
                    if (t >= 10.0 && t <= 10.4) {
                        Triple(0.0, 0.0, GRAVITY * 3)
                    } else {
                        Triple(
                            0.0,
                            0.0,
                            GRAVITY,
                        )
                    }
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue("vertical-only shock must not register, got $events", events.isEmpty())
    }

    /**
     * The event-splitting guard: one continuous 3-second brake, with a brief dip in the middle where
     * the driver eased off. That is one brake, not two, and its duration is a scoring input so it
     * has to come out close to the real 3 seconds.
     */
    @Test
    fun oneSustainedBrakeWithADipStaysOneEvent() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0, speedAt = speedDrop(20.0, 3.43, 10.0, 13.0)) { t ->
                    // 0.35 g held for three seconds, with the driver lifting off entirely for 200 ms
                    // in the middle — long enough to fall under the sustain level, short enough that
                    // the merge gap should fold it back into the same event.
                    val decel =
                        when {
                            t >= 10.5 && t < 10.7 -> 0.0
                            t >= 10.0 && t <= 13.0 -> 3.43
                            else -> 0.0
                        }
                    Triple(
                        -decel,
                        0.0,
                        GRAVITY,
                    ) // device is level, so -x is west = braking eastbound
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertEquals("the dip must not split the brake, got $events", 1, events.size)
        Assert.assertEquals(RideEventType.BRAKING, events[0].type)
        Assert.assertTrue(
            "duration should be ~3000 ms, was ${events[0].durationMs}",
            events[0].durationMs in 2900..3200,
        )
        Assert.assertTrue(
            "should start ~10 s in, was ${events[0].startOffsetMs}",
            events[0].startOffsetMs in 10_000..10_200,
        )
    }

    /**
     * The case force alone gets wrong: a tight residential corner. 18 km/h on a ~6 m radius is
     * v²/r ≈ 0.43 g — higher than plenty of events worth flagging — but the driver winds the wheel on
     * over a second and a half, so nothing about it is abrupt. It must not register.
     */
    @Test
    fun smoothTightCornerIsNotHarsh() {
        // Lateral force ramped in linearly over 1.5 s, held, then eased off just as gently. The
        // yaw rate follows the same profile (a = v·ω), so the gyro corroborates a real corner and
        // the jerk gate alone is what must reject it.
        fun profile(t: Double) =
            when {
                t < 10.0 -> 0.0
                t < 11.5 -> (t - 10.0) / 1.5
                t < 13.0 -> 1.0
                t < 14.5 -> (14.5 - t) / 1.5
                else -> 0.0
            }
        val events =
            detectRideEvents(
                ride(seconds = 20.0, speedMps = 5.0, yawRateAt = { t -> (4.2 / 5.0) * profile(t) }) { t ->
                    // Heading is east, so a lateral force lies on the device's north axis.
                    Triple(0.0, 4.2 * profile(t), GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue(
            "a smoothly-driven tight corner must not register, got $events",
            events.isEmpty(),
        )
    }

    /**
     * The mirror case: a stab at the brake pedal reaching only 0.32 g, modest force by the standards
     * of an emergency stop, but arriving almost at once. Both halves of the AND are satisfied — the
     * rate clears the gate and the peak clears the floor — so it registers.
     */
    @Test
    fun abruptBrakeToModestForceIsHarsh() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0, speedAt = speedDrop(20.0, 3.14, 10.0, 11.0)) { t ->
                    val decel =
                        if (t >= 10.0 && t < 11.0) 3.14 else 0.0 // 0.32 g, applied as a step
                    Triple(-decel, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertEquals("expected one event, got $events", 1, events.size)
        Assert.assertEquals(RideEventType.BRAKING, events[0].type)
        Assert.assertTrue(
            "peak jerk should clear the gate, was ${events[0].peakJerkGPerS}",
            events[0].peakJerkGPerS > 1.0,
        )
        Assert.assertTrue(
            "peak force should clear the floor, was ${events[0].peakG}",
            events[0].peakG > 0.25,
        )
    }

    /**
     * Braking can be initiated perfectly smoothly and still end up somewhere no passenger enjoys.
     * A gradual 3-second build to 0.6 g has low jerk throughout, so only the magnitude path catches
     * it — and it should, because 0.6 g is a hard stop however politely it started.
     */
    @Test
    fun smoothButHardBrakingIsStillHarsh() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0, speedAt = speedDrop(20.0, 2.95, 10.0, 13.0)) { t ->
                    val decel =
                        if (t >= 10.0 && t < 13.0) 5.9 * (t - 10.0) / 3.0 else 0.0 // ramp to 0.6 g
                    Triple(-decel, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertEquals(
            "a smooth but hard stop must still register, got $events",
            1,
            events.size,
        )
        Assert.assertEquals(RideEventType.BRAKING, events[0].type)
        Assert.assertTrue(
            "peak should exceed the high-force path, was ${events[0].peakG}",
            events[0].peakG >= 0.50,
        )
        // Below the jerk gate, so this event can only have come through the high-force bypass. If
        // that bypass were removed this fails rather than quietly passing via the ordinary path.
        Assert.assertTrue(
            "and it should be smooth, was ${events[0].peakJerkGPerS} g/s",
            events[0].peakJerkGPerS < 1.0,
        )
    }

    /**
     * The other half of the AND. This maneuver is abrupt enough to clear the jerk gate outright —
     * it opens an event — but tops out at 0.18 g, under the peak floor, so it is discarded on close.
     * Sized deliberately: a smaller twitch would fail the jerk gate too and stop testing the floor.
     */
    @Test
    fun abruptButTrivialTwitchIsRejected() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0, speedAt = speedDrop(20.0, 1.77, 10.0, 11.0)) { t ->
                    val decel =
                        if (t >= 10.0 && t < 11.0) 1.77 else 0.0 // 0.18 g, applied instantly
                    Triple(-decel, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue(
            "abrupt but under the force floor must be discarded, got $events",
            events.isEmpty(),
        )
    }

    /** A jolt shorter than minDurationMs is road noise, not a driving event. */
    @Test
    fun briefJoltIsNotAnEvent() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0) { t ->
                    if (t >= 10.0 && t <= 10.06) {
                        Triple(-9.8, 0.0, GRAVITY)
                    } else {
                        Triple(
                            0.0,
                            0.0,
                            GRAVITY,
                        )
                    }
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue("a 60 ms jolt must be rejected, got $events", events.isEmpty())
    }

    /** Hard braking while crawling is a parking maneuver, and is gated out by speed. */
    @Test
    fun harshBrakingBelowTheSpeedGateIsIgnored() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0, speedMps = 2.0) { t ->
                    if (t >= 10.0 && t <= 12.0) {
                        Triple(-3.43, 0.0, GRAVITY)
                    } else {
                        Triple(
                            0.0,
                            0.0,
                            GRAVITY,
                        )
                    }
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue(
            "below the speed gate nothing should register, got $events",
            events.isEmpty(),
        )
    }

    /** Spinning the phone in your hand produces huge readings; the gyro gate must discard them. */
    @Test
    fun phoneHandlingIsRejected() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0, gyroRadPerSec = 6.0) { t ->
                    if (t >= 10.0 && t <= 12.0) {
                        Triple(-3.43, 0.0, GRAVITY)
                    } else {
                        Triple(
                            0.0,
                            0.0,
                            GRAVITY,
                        )
                    }
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue("handling the phone must not score, got $events", events.isEmpty())
    }

    /**
     * The reason detection rests on the calibrated axis and nothing else. The car drives cleanly
     * east for 40 s, then the GPS starts zig-zagging north/south — a fix wandering the way it does at
     * low speed or under poor sky view — while the car keeps going east and brakes hard.
     *
     * The split happens in the device frame against the calibrated axis, so the wandering course
     * can't touch it: the brake stays a brake. And when no axis could be calibrated there is no
     * per-sample GPS fallback to fall into — that fallback used to project force onto a course that
     * lags the car (a slalom's course reads straight while the car swings), manufacturing braking
     * and acceleration out of steering. No axis means no events, never misfiled ones.
     */
    @Test
    fun calibratedAxisSurvivesGpsGoingWrongAndNoAxisMeansNoEvents() {
        val recorded =
            ride(
                seconds = 55.0,
                // Clean eastward run to calibrate from, then the fix starts flip-flopping.
                travelBearingAt = { t ->
                    if (t < 40.0) {
                        90.0
                    } else if (t.toInt() % 2 == 0) {
                        0.0
                    } else {
                        180.0
                    }
                },
                speedAt = speedDrop(20.0, 3.43, 45.0, 47.0),
            ) { t ->
                // The car is still travelling east throughout, and brakes at 45 s.
                val decel = if (t >= 45.0 && t < 47.0) 3.43 else 0.0 // 0.35 g
                Triple(-decel, 0.0, GRAVITY)
            }

        val events = detectRideEvents(recorded, rideStartElapsedNanos = 0L)
        Assert.assertEquals("expected exactly one event, got $events", 1, events.size)
        Assert.assertEquals(
            "must stay braking despite the GPS heading, got ${events[0].type}",
            RideEventType.BRAKING,
            events[0].type,
        )

        // The contrast that gives this test teeth: starve calibration of samples and the very same
        // ride must report nothing at all — not the brake in some other bucket, which is what a
        // per-sample GPS-heading fallback produced.
        val noAxis =
            detectRideEvents(recorded, 0L, RideEventConfig(alignmentMinSamples = Int.MAX_VALUE))
        Assert.assertTrue(
            "without a calibrated axis nothing may be reported, got $noAxis",
            noAxis.isEmpty(),
        )
    }

    /**
     * The slalom regression, from a real ride whose slalom was stored as acceleration *and*
     * cornering *and* braking at once. A slalom on a straight road swings the lateral force while
     * the GPS course barely moves and the speed doesn't change at all. In the device frame the
     * force lands cleanly on the lateral axis: harsh steering, and nothing longitudinal — the old
     * GPS-heading fallback projected the same swings onto the lagging course and invented
     * braking-plus-acceleration out of them.
     */
    @Test
    fun slalomOnAStraightRoadIsCorneringOnly() {
        val amplitude = 4.0 // ±0.41 g, swung at 0.5 Hz: wheel-flick abrupt, ~1.3 g/s folded
        val events =
            detectRideEvents(
                ride(
                    seconds = 30.0,
                    yawRateAt = { t ->
                        if (t in 12.0..18.0) amplitude * sin(Math.PI * (t - 12.0)) / 20.0 else 0.0
                    },
                ) { t ->
                    val lateral = if (t in 12.0..18.0) amplitude * sin(Math.PI * (t - 12.0)) else 0.0
                    Triple(0.0, lateral, GRAVITY) // heading east: lateral lies on the north axis
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue(
            "a slalom must not read as braking or acceleration, got $events",
            events.none { it.type == RideEventType.BRAKING || it.type == RideEventType.ACCELERATION },
        )
        Assert.assertTrue(
            "and the harsh steering itself must register, got $events",
            events.any { it.type == RideEventType.CORNERING },
        )
    }

    /**
     * A rotation vector whose yaw wobbles against the true course — the magnetometer inside a car.
     * ±35° of scatter fails the old 0.95 coherence bar (mean ≈ 0.91), which used to throw the ride
     * to the GPS-heading fallback and smear this brake across the wrong buckets. The mean axis is
     * still accurate, the split runs in the device frame where yaw cancels, so the brake must come
     * out as exactly one braking event — full magnitude, no cornering invented from the wobble.
     */
    @Test
    fun yawWobblingRotationVectorStillResolvesCleanBraking() {
        val events =
            detectRideEvents(
                ride(
                    seconds = 55.0,
                    quaternionAt = { t ->
                        val yaw = Math.toRadians(35.0) * sin(2 * Math.PI * 0.15 * t)
                        floatArrayOf(0f, 0f, sin(yaw / 2).toFloat(), cos(yaw / 2).toFloat())
                    },
                    speedAt = speedDrop(20.0, 3.43, 45.0, 47.0),
                ) { t ->
                    val decel = if (t >= 45.0 && t < 47.0) 3.43 else 0.0 // 0.35 g eastward brake
                    Triple(-decel, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertEquals("expected exactly one event, got $events", 1, events.size)
        Assert.assertEquals(RideEventType.BRAKING, events[0].type)
        Assert.assertTrue(
            "the wobble must not eat the magnitude, was ${events[0].peakG}",
            events[0].peakG > 0.33,
        )
    }

    /**
     * The fake-corner regression, from a real emergency stop that was stored as braking *plus*
     * cornering. A calibration bias — here a rotation vector with a constant 30° yaw error, the
     * magnetometer's doing in a real car — leaks sin 30° of a hard brake onto the lateral axis:
     * 0.45 g of "cornering" from a dead-straight stop, over both the peak floor and the entry gate.
     * The gyro shows no yaw, so the cornering signal (the lower of felt lateral and v·ω) stays at
     * zero and only the brake may be reported.
     */
    @Test
    fun brakeLeakThroughABiasedAxisCannotBecomeCornering() {
        val yaw = Math.toRadians(30.0)
        val biased = floatArrayOf(0f, 0f, sin(yaw / 2).toFloat(), cos(yaw / 2).toFloat())
        val events =
            detectRideEvents(
                ride(
                    seconds = 20.0,
                    quaternion = biased,
                    speedMps = 24.0,
                    speedAt = speedDrop(24.0, 8.83, 10.4, 12.0),
                ) { t ->
                    // 0.9 g reached in 0.4 s and held — an emergency stop, abrupt on purpose so the
                    // leaked lateral share clears the cornering entry gate if nothing stops it.
                    val decel =
                        when {
                            t < 10.0 -> 0.0
                            t < 10.4 -> 8.83 * (t - 10.0) / 0.4
                            t < 12.0 -> 8.83
                            else -> 0.0
                        }
                    Triple(-decel, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue(
            "a straight-line stop must not produce cornering, got $events",
            events.none { it.type == RideEventType.CORNERING },
        )
        Assert.assertEquals("the brake itself must survive, got $events", 1, events.size)
        Assert.assertEquals(RideEventType.BRAKING, events[0].type)
    }

    /**
     * The mount-lurch regression, from a real ride where flooring the throttle made the loosely
     * lying phone slide backwards — a textbook 0.3 g braking spike on the accelerometer while the
     * car's Doppler speed was rising. The accelerometer measures the phone; only the car earns
     * events. With no speed drop to corroborate it, the "brake" must be discarded.
     */
    @Test
    fun phantomBrakingWithoutASpeedDropIsVetoed() {
        val events =
            detectRideEvents(
                ride(seconds = 20.0) { t ->
                    // An abrupt half-second backwards jolt — clears the jerk gate, the peak floor
                    // and the minimum duration, so only the Doppler check stands in its way.
                    val jolt = if (t >= 10.0 && t < 10.5) 3.2 else 0.0
                    Triple(-jolt, 0.0, GRAVITY)
                },
                rideStartElapsedNanos = 0L,
            )

        Assert.assertTrue(
            "a brake the car's speed contradicts must be discarded, got $events",
            events.isEmpty(),
        )
    }

    /** A poorly-fixed stretch produces no events at all, rather than events built on a bad position. */
    @Test
    fun inaccurateFixesSuppressDetection() {
        val clean =
            ride(seconds = 20.0, speedAt = speedDrop(20.0, 3.43, 10.0, 12.0)) { t ->
                if (t >= 10.0 && t < 12.0) Triple(-3.43, 0.0, GRAVITY) else Triple(0.0, 0.0, GRAVITY)
            }
        Assert.assertEquals(
            "control: the same ride with good fixes registers",
            1,
            detectRideEvents(
                clean,
                0L,
            ).size,
        )

        val vague = clean.copy(locations = clean.locations.map { it.copy(accuracy = 80f) })
        Assert.assertTrue(
            "fixes the receiver calls poor must not produce events",
            detectRideEvents(
                vague,
                0L,
            ).isEmpty(),
        )
    }

    /**
     * Turning into a side street, and threading a car park. Both reach past 0.5 g laterally purely
     * because lateral force is v²/r and a tight radius is what those maneuvers *are* — yet both are
     * driven smoothly, well under the jerk gate. Neither may register.
     *
     * This is the regression for the high-force bypass having applied to cornering, which made both
     * of these events regardless of how gently they were driven. The braking assertion at the end is
     * what stops the fix from being "delete the bypass".
     */
    @Test
    fun smoothLowSpeedTurnsAreNotHarshEvenAboveHalfAG() {
        // Lateral force ramped in over turnInSec, held, then unwound just as gently — with the yaw
        // rate the geometry implies (ω = v/r), so the gyro agrees this is genuine cornering.
        fun corner(
            speedMps: Double,
            radiusM: Double,
            turnInSec: Double,
        ): RideSamples {
            fun held(t: Double) =
                when {
                    t < 10.0 -> 0.0
                    t < 10.0 + turnInSec -> (t - 10.0) / turnInSec
                    t < 12.5 -> 1.0
                    t < 12.5 + turnInSec -> (12.5 + turnInSec - t) / turnInSec
                    else -> 0.0
                }
            return ride(
                seconds = 25.0,
                speedMps = speedMps,
                yawRateAt = { t -> (speedMps / radiusM) * held(t) },
            ) { t ->
                // heading is east, so lateral force lies on the north axis
                Triple(0.0, (speedMps * speedMps / radiusM) * held(t), GRAVITY)
            }
        }

        // 30 km/h into a side street: 0.59 g, but built over 0.8 s = 0.73 g/s, under the jerk gate.
        val sideStreet = detectRideEvents(corner(8.3, 12.0, 0.8), 0L)
        Assert.assertTrue(
            "a smooth turn into a side street must not register, got $sideStreet",
            sideStreet.isEmpty(),
        )

        // 20 km/h through a tight car-park corner: 0.51 g at a positively lazy 0.43 g/s.
        val carPark = detectRideEvents(corner(5.5, 6.0, 1.2), 0L)
        Assert.assertTrue(
            "a smooth tight low-speed corner must not register, got $carPark",
            carPark.isEmpty(),
        )

        // But the bypass must survive for braking, where 0.5 g is a hard stop at any speed.
        val smoothStop =
            detectRideEvents(
                ride(seconds = 20.0, speedAt = speedDrop(20.0, 2.95, 10.0, 13.0)) { t ->
                    val decel =
                        if (t >= 10.0 && t < 13.0) 5.9 * (t - 10.0) / 3.0 else 0.0 // ramp to 0.6 g
                    Triple(-decel, 0.0, GRAVITY)
                },
                0L,
            )
        Assert.assertEquals(
            "braking must keep its force bypass, got $smoothStop",
            1,
            smoothStop.size,
        )
        Assert.assertEquals(RideEventType.BRAKING, smoothStop[0].type)
    }

    /**
     * Parking, with the GPS lying about it. A full-lock maneuver at 3 m/s pulls a real 0.35 g
     * laterally and the wheel goes over fast enough to clear the jerk gate, so on GPS speed alone —
     * which claims 20 m/s, the kind of nonsense a fix produces while shuffling around a car park —
     * this would register.
     *
     * It doesn't, because lateral force and yaw rate together give speed without GPS: a = v·ω, so
     * v = a/ω ≈ 3 m/s, well under the gate. The contrast at the end is what proves the IMU estimate
     * is doing the work rather than something else rejecting it.
     */
    @Test
    fun imuSpeedRejectsParkingWhenGpsLiesAboutSpeed() {
        val lateral = 3.43 // 0.35 g
        val trueSpeed = 3.0 // ~11 km/h
        val yaw = lateral / trueSpeed // 1.14 rad/s: full lock, ~2.6 m radius

        // Wheel wound on over 0.3 s. Force and yaw share a profile, so a/ω stays the true speed.
        fun profile(t: Double) =
            when {
                t < 10.0 -> 0.0
                t < 10.3 -> (t - 10.0) / 0.3
                t < 12.0 -> 1.0
                else -> 0.0
            }

        val parking =
            ride(
                seconds = 20.0,
                speedMps = 20.0, // what the GPS claims
                yawRateAt = { t -> yaw * profile(t) },
            ) { t -> Triple(0.0, lateral * profile(t), GRAVITY) }

        Assert.assertTrue(
            "IMU speed should veto the lying GPS, got ${detectRideEvents(parking, 0L)}",
            detectRideEvents(
                parking,
                0L,
            ).isEmpty(),
        )

        // Starve the IMU estimate by demanding an impossible yaw rate, and the false positive returns.
        val gpsOnly =
            detectRideEvents(parking, 0L, RideEventConfig(minYawForImuSpeedRadPerS = 1_000.0))
        Assert.assertEquals(
            "without the IMU check the lying GPS lets it through, got $gpsOnly",
            1,
            gpsOnly.size,
        )
        Assert.assertEquals(RideEventType.CORNERING, gpsOnly[0].type)
    }

    /** The guard against over-suppressing: a genuinely harsh swerve at speed must still register. */
    @Test
    fun imuSpeedDoesNotSuppressAGenuineFastCorner() {
        val lateral = 4.4 // 0.45 g
        val trueSpeed = 15.0 // 54 km/h
        val yaw = lateral / trueSpeed // 0.29 rad/s, a ~51 m radius

        fun profile(t: Double) =
            when {
                t < 10.0 -> 0.0

                t < 10.3 -> (t - 10.0) / 0.3

                // wound on fast: ~1.5 g/s
                t < 12.0 -> 1.0

                else -> 0.0
            }

        val swerve =
            ride(
                seconds = 20.0,
                speedMps = trueSpeed,
                yawRateAt = { t -> yaw * profile(t) },
            ) { t -> Triple(0.0, lateral * profile(t), GRAVITY) }

        val events = detectRideEvents(swerve, 0L)
        Assert.assertEquals(
            "a hard swerve at 54 km/h must still register, got $events",
            1,
            events.size,
        )
        Assert.assertEquals(RideEventType.CORNERING, events[0].type)
    }

    /**
     * readRideSamples thins the two supporting streams to keep a long ride inside the heap, which is
     * only safe if detection can't tell. Same ride, full 50 Hz versus every fifth sample — 10 Hz,
     * more aggressive than the 25 Hz orientation actually ships with, so passing here is the
     * stronger statement. Acceleration is never thinned and is not varied here.
     */
    @Test
    fun thinningOrientationAndGyroDoesNotChangeDetection() {
        val yaw = 4.4 / 15.0

        fun profile(t: Double) =
            when {
                t < 10.0 -> 0.0
                t < 10.3 -> (t - 10.0) / 0.3
                t < 12.0 -> 1.0
                else -> 0.0
            }
        val full =
            ride(seconds = 20.0, speedMps = 15.0, yawRateAt = { t -> yaw * profile(t) }) { t ->
                Triple(0.0, 4.4 * profile(t), GRAVITY)
            }
        // Keep every fifth motion sample of the two thinned streams: 50 Hz -> 10 Hz.
        val thinned =
            full.copy(
                gyro = full.gyro.filterIndexed { i, _ -> i % 5 == 0 },
                rotation = full.rotation.filterIndexed { i, _ -> i % 5 == 0 },
            )

        val fromFull = detectRideEvents(full, 0L)
        val fromThinned = detectRideEvents(thinned, 0L)

        Assert.assertEquals(
            "control: the full-rate ride must produce an event to compare",
            1,
            fromFull.size,
        )
        Assert.assertEquals(fromFull.size, fromThinned.size)
        Assert.assertEquals(fromFull[0].type, fromThinned[0].type)
        Assert.assertEquals(
            fromFull[0].startOffsetMs.toDouble(),
            fromThinned[0].startOffsetMs.toDouble(),
            40.0,
        )
        Assert.assertEquals(fromFull[0].peakG, fromThinned[0].peakG, 0.005)
        Assert.assertEquals(fromFull[0].peakJerkGPerS, fromThinned[0].peakJerkGPerS, 0.05)
    }

    /** A missing sensor means no score, never a guessed one. */
    @Test
    fun missingRotationVectorYieldsNothing() {
        val full = ride(seconds = 20.0) { Triple(-3.43, 0.0, GRAVITY) }

        Assert.assertTrue(detectRideEvents(full.copy(rotation = emptyList()), 0L).isEmpty())
        Assert.assertTrue(detectRideEvents(full.copy(accel = emptyList()), 0L).isEmpty())
        Assert.assertTrue(detectRideEvents(full.copy(locations = emptyList()), 0L).isEmpty())
    }

    /**
     * Runs the detector exactly as [detectRideEvents] does, but keeps the dynamics profile instead of
     * the events. Both come out of one pass over the same conditioned signal.
     */
    private fun dynamicsOf(
        samples: RideSamples,
        config: RideEventConfig = RideEventConfig(),
    ): RideDynamics {
        val fixes = kalmanFilterLocations(samples.locations)
        val detector = StreamingDetector(estimateForwardAxis(fixes, samples.rotation, config), config, 0L)
        val merged = (fixes + samples.accel + samples.gyro + samples.rotation).sortedBy { it.t }
        for (sample in merged) {
            when (sample) {
                is LocationSample -> detector.onFix(sample)
                is MotionSample -> detector.onMotion(sample)
            }
        }
        detector.finish()
        return detector.dynamics()
    }

    /** Ordinary cruising is measurable end to end, and none of it counts as roughness. */
    @Test
    fun cruisingIsFullyMeasuredAndEntirelySmooth() {
        val dynamics = dynamicsOf(ride(seconds = 300.0) { Triple(0.0, 0.0, GRAVITY) })

        Assert.assertTrue("expected most of the ride measured, got ${dynamics.coverage}", dynamics.coverage > 0.9)
        Assert.assertNotNull("a clean ride is still a scoreable one", scoreRide(dynamics))
    }

    /**
     * The hole this profile exists to close. A ride spent below the speed gate — a long crawl around
     * a car park — produces no events at all, which on its own is indistinguishable from flawless
     * motorway driving. Only the gap between measured and elapsed time tells them apart, so the
     * profile has to record both and the score has to come back absent rather than perfect.
     */
    @Test
    fun timeBelowTheSpeedGateIsElapsedButNotMeasured() {
        val crawling = dynamicsOf(ride(seconds = 300.0, speedMps = 2.0) { Triple(0.0, 0.0, GRAVITY) })

        Assert.assertEquals("nothing above the gate", 0.0, crawling.qualifiedSeconds, 0.5)
        Assert.assertTrue("time still elapsed, got ${crawling.totalSeconds}", crawling.totalSeconds > 250)
        Assert.assertNull("unmeasurable is not flawless", scoreRide(crawling))
    }

    /**
     * End to end through the real detector: stabs at the brakes have to land in the profile's upper
     * jerk bins and drag the score down, or nothing else in the scoring chain matters.
     */
    @Test
    fun harshBrakingLandsInTheProfileAndLowersTheScore() {
        val smooth = dynamicsOf(ride(seconds = 300.0) { Triple(0.0, 0.0, GRAVITY) })
        val harsh =
            dynamicsOf(
                // Three 0.45 g stabs a minute apart, each held a second.
                ride(seconds = 300.0) { t ->
                    if (t % 60.0 in 30.0..31.0) Triple(-4.41, 0.0, GRAVITY) else Triple(0.0, 0.0, GRAVITY)
                },
            )

        // Bins from index 10 up start at 1.0 g/s, which is where braking becomes an event.
        Assert.assertTrue(
            "expected time past the braking jerk threshold, got ${harsh.braking.jerkSeconds}",
            harsh.braking.jerkSeconds
                .drop(10)
                .sum() > 0f,
        )
        val smoothScore = scoreRide(smooth)!!.total
        val harshScore = scoreRide(harsh)!!.total
        Assert.assertTrue("expected $harshScore well below $smoothScore", smoothScore - harshScore > 5)
    }

    /**
     * Repeated braking to [peakG], eased on and off over [onset] seconds, once every 30 s for twenty
     * minutes. Real pedal ramps rather than step changes: how fast a brake is applied is the whole
     * quantity being scored, so a synthetic that jumps instantly would test the low-pass filter
     * instead of the driving.
     */
    private fun brakingRide(
        peakG: Double,
        onset: Double,
    ) = ride(seconds = 1200.0) { t ->
        val phase = t % 30.0
        val fraction =
            when {
                phase < onset -> phase / onset
                phase < onset + 1.0 -> 1.0
                phase < onset * 2 + 1.0 -> 1 - (phase - onset - 1.0) / onset
                else -> 0.0
            }
        Triple(-peakG * GRAVITY * fraction, 0.0, GRAVITY)
    }

    /**
     * The requirement discrete events could never satisfy on their own (ANL-01): braking firm enough
     * to be unpleasant but not firm enough to trigger anything must still cost something.
     *
     * The sporty case is the one that matters — it fires **zero** events, so any score built by
     * counting events would call it indistinguishable from a gentle drive, yet it is plainly not the
     * same driving. Scoring the whole distribution rather than its tail is what separates them, and
     * this test fails the moment that stops being true.
     */
    @Test
    fun nearMissesCostSomethingEvenWhenNoEventFires() {
        val gentle = dynamicsOf(brakingRide(peakG = 0.15, onset = 2.0))
        val sportyRide = brakingRide(peakG = 0.45, onset = 0.5)
        val sporty = dynamicsOf(sportyRide)

        Assert.assertEquals("the sporty drive must not trigger events", 0, detectRideEvents(sportyRide, 0L).size)
        val gentleScore = scoreRide(gentle)!!.total
        val sportyScore = scoreRide(sporty)!!.total
        Assert.assertTrue(
            "gentle braking should cost nothing at all",
            gentle.braking.jerkSeconds
                .drop(5)
                .sum() == 0f,
        )
        Assert.assertTrue(
            "near-miss braking should cost real points, got $sportyScore against $gentleScore",
            gentleScore - sportyScore in 5..30,
        )
    }

    /** Severity has to order the score across the whole range, not just above the event threshold. */
    @Test
    fun harsherBrakingScoresProgressivelyWorse() {
        val scores =
            listOf(0.15 to 2.0, 0.35 to 0.8, 0.45 to 0.5, 0.60 to 0.35).map { (peak, onset) ->
                scoreRide(dynamicsOf(brakingRide(peak, onset)))!!.total
            }
        Assert.assertEquals("expected monotonically worse, got $scores", scores.sortedDescending(), scores)
        Assert.assertTrue("expected a wide spread, got $scores", scores.first() - scores.last() > 50)
    }
}
