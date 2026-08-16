package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.data.RideEventType
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
 * the production math and pass regardless. The rest covers the event state machine.
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
        deviceAccelAt: (Double) -> Triple<Double, Double, Double>,
    ): RideSamples {
        // Positions are integrated along the travel bearing rather than assumed straight, so a test
        // can send the GPS somewhere the car isn't actually going.
        val locations = ArrayList<LocationSample>()
        var lat = 50.0
        var lon = 8.0
        for (second in 0..seconds.toInt()) {
            locations.add(
                LocationSample(
                    t = second * 1_000_000_000L,
                    lat = lat,
                    lon = lon,
                    alt = 0.0,
                    speed = speedMps.toFloat(),
                    bearing = 90f, // overwritten by the Kalman pass inside the detector
                    accuracy = 5f,
                ),
            )
            val heading = Math.toRadians(travelBearingAt(second.toDouble()))
            lat += (speedMps * cos(heading)) / METERS_PER_DEGREE_LAT
            lon += (speedMps * sin(heading)) / METERS_PER_DEGREE_LON
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
            rotation.add(
                MotionSample(
                    nanos,
                    MotionSensor.ROTATION,
                    quaternion[0],
                    quaternion[1],
                    quaternion[2],
                    quaternion[3],
                ),
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
            detectRideEvents(
                ride(seconds = 20.0, quaternion = YAWED_90) { t ->
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
                ride(seconds = 20.0) { t ->
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
        val events =
            detectRideEvents(
                ride(seconds = 20.0, speedMps = 5.0) { t ->
                    // Lateral force ramped in linearly over 1.5 s, held, then eased off just as gently.
                    val lateral =
                        when {
                            t < 10.0 -> 0.0
                            t < 11.5 -> 4.2 * (t - 10.0) / 1.5
                            t < 13.0 -> 4.2
                            t < 14.5 -> 4.2 * (14.5 - t) / 1.5
                            else -> 0.0
                        }
                    // Heading is east, so a lateral force lies on the device's north axis.
                    Triple(0.0, lateral, GRAVITY)
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
                ride(seconds = 20.0) { t ->
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
                ride(seconds = 20.0) { t ->
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
                ride(seconds = 20.0) { t ->
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
     * The reason heading is calibrated rather than read from GPS per sample. The car drives cleanly
     * east for 40 s, then the GPS starts zig-zagging north/south — a fix wandering the way it does at
     * low speed or under poor sky view — while the car keeps going east and brakes hard.
     *
     * Heading from GPS would call that brake a *corner*, because a deceleration pointing east is
     * perpendicular to a heading pointing north. Heading from the calibrated forward axis still says
     * east, so it reads as braking. The assertion on type is the whole point: get the axis wrong and
     * the force doesn't vanish, it lands in the wrong bucket.
     */
    @Test
    fun calibratedHeadingSurvivesGpsGoingWrong() {
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
        // ride mis-files the brake as a corner. Without it the assertions above would still pass if
        // alignment silently did nothing and the GPS heading happened to be good enough anyway.
        // Asserted on type rather than count: with the heading swinging, the force lands in whichever
        // bucket the bad axis points at, and can smear across more than one. What matters is that the
        // brake stops being recognised as a brake at all.
        val gpsOnly =
            detectRideEvents(recorded, 0L, RideEventConfig(alignmentMinSamples = Int.MAX_VALUE))
        Assert.assertTrue(
            "GPS-only heading should mis-file the brake, got $gpsOnly",
            gpsOnly.none { it.type == RideEventType.BRAKING },
        )
        Assert.assertTrue(
            "and should still see the force somewhere, got $gpsOnly",
            gpsOnly.isNotEmpty(),
        )
    }

    /** A poorly-fixed stretch produces no events at all, rather than events built on a bad position. */
    @Test
    fun inaccurateFixesSuppressDetection() {
        val clean = ride(seconds = 20.0) { t -> if (t >= 10.0 && t < 12.0) Triple(-3.43, 0.0, GRAVITY) else Triple(0.0, 0.0, GRAVITY) }
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
        // Lateral force ramped in over turnInSec, held, then unwound just as gently.
        fun corner(
            speedMps: Double,
            radiusM: Double,
            turnInSec: Double,
        ) = ride(seconds = 25.0, speedMps = speedMps) { t ->
            val lateral = speedMps * speedMps / radiusM
            val held =
                when {
                    t < 10.0 -> 0.0
                    t < 10.0 + turnInSec -> lateral * (t - 10.0) / turnInSec
                    t < 12.5 -> lateral
                    t < 12.5 + turnInSec -> lateral * (12.5 + turnInSec - t) / turnInSec
                    else -> 0.0
                }
            Triple(0.0, held, GRAVITY) // heading is east, so lateral force lies on the north axis
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
                ride(seconds = 20.0) { t ->
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
}
