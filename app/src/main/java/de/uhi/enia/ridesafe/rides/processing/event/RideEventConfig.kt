package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.data.RideEvent

/**
 * Thresholds for one direction of travel: braking, acceleration or cornering.
 *
 * They differ per direction because the physics do. Brakes bite harder and faster than an engine
 * pushes, and cornering force is geometry rather than pedal input, so one set of numbers is wrong
 * for at least two of the three.
 *
 * The ordinary path is an AND — [enterJerkGPerS] *and* a peak clearing [minPeakG] — because either
 * alone misjudges real driving: a tight residential corner pulls 0.4 g while being perfectly smooth,
 * and an everyday stop can be sudden without ever amounting to much. [highPeakG] is the single
 * exception, opening on force alone.
 *
 * @property enterJerkGPerS How fast the force must build to open an event, in g per second. The
 * primary trigger, because harshness is abruptness rather than force. Roughly 1.0 g/s (≈10 m/s³) is
 * the conventional line; ordinary braking builds at 0.2–0.5 g/s. Raise it and only sharper
 * maneuvers register; lower it and normal driving starts qualifying — at 0.6 g/s a routine stop
 * reaches 0.25 g in 0.42 s and clears the gate, which is how nearly every stop once became an event.
 *
 * @property exitJerkGPerS The rate below which abruptness no longer keeps an open event alive, in g
 * per second. Deliberately under [enterJerkGPerS]; the gap is hysteresis, so a signal hovering at
 * the trigger produces one event rather than a burst. Raise it toward [enterJerkGPerS] and that
 * hysteresis narrows, fragmenting borderline maneuvers into several events; lower it and events
 * stay open on rate alone, stretching reported durations.
 *
 * @property minPeakG The force floor for this direction, in g, doing two jobs. It keeps an event
 * alive through the steady middle of a maneuver, where jerk is near zero by definition — without it
 * [RideEvent.durationMs] would measure how long the maneuver was *abrupt* rather than how long it
 * lasted. It is also the floor a finished event's peak must clear to be kept at all, which discards
 * a sudden but negligible twitch. That second check is a verdict on the completed event, never a
 * per-sample gate: jerk peaks at a maneuver's onset while force is still near zero, so gating per
 * sample would throw away the very spike that opened it. Raise it and marginal events disappear
 * while survivors report shorter durations, since an event spans only the time above this level.
 *
 * @property highPeakG Force that opens an event on its own regardless of how gently it arrived, in
 * g; null disables the bypass for this direction. It exists because braking can be applied
 * perfectly smoothly and still end up somewhere no passenger enjoys. It is null for cornering on
 * purpose: lateral force is v²/r, so a tight radius at low speed clears any fixed threshold with
 * nothing harsh happening — turning into a side street reaches 0.6 g at 30 km/h. Jerk handles
 * cornering by itself, since lateral jerk carries a v² factor that keeps low-speed steering under
 * the gate. Lower it and smooth-but-firm maneuvers start counting; set it non-null for cornering
 * and every tight low-speed turn becomes an event again.
 */
data class DirectionThresholds(
    val enterJerkGPerS: Double,
    val exitJerkGPerS: Double,
    val minPeakG: Double,
    val highPeakG: Double?,
)

/**
 * Detection knobs for ride-event analysis (NFR-08).
 *
 * Per-direction thresholds live in [DirectionThresholds]; everything here applies to all three
 * directions alike, or governs the signal conditioning and gating that runs before them.
 *
 * Changing any value here invalidates stored events, so bump
 * [de.uhi.enia.ridesafe.rides.processing.RideEventStage.version] alongside it — the pipeline then
 * re-analyzes every ride from its raw samples on next launch. That loop is the entire
 * tuning workflow, and it is why events store their measured magnitudes rather than a verdict:
 * anything decidable at read time should be, and never costs a re-analysis.
 *
 * @property braking Braking thresholds. Keeps a force bypass, since a 0.45 g stop is harsh however
 * gently it was applied.
 *
 * @property acceleration Acceleration thresholds. Lower bypass and lower jerk gate than braking,
 * because torque builds more slowly than brakes bite and an ordinary car cannot pull 0.5 g outside
 * a standing start.
 *
 * @property cornering Cornering thresholds. No force bypass, and a slightly higher floor since
 * 0.25 g is an unremarkable corner. The entry gate sits a notch under the braking one: the
 * cornering signal is the *lower* of accelerometer lateral and gyro-derived v·ω, so the false
 * positives a stricter gate once guarded against — longitudinal bleed, mount wobble — are already
 * dead, and the razor-edge misses (a real evasive steer measured at 0.88 g/s against a 0.9 gate)
 * were what the strictness actually bought.
 *
 * @property jerkBaselineMs The time baseline over which rate of change is measured. Differencing
 * adjacent 50 Hz samples divides by 0.02 s, turning even 0.003 g of residual ripple into 0.15 g/s —
 * the same range as the genuine jerk of smooth driving, so the measurement would be mostly noise.
 * Differencing across ~100 ms cuts that fivefold while still resolving events lasting several
 * hundred ms. Shorten it and jerk gets noisier, producing spurious events; lengthen it and brief
 * stabs are averaged away.
 *
 * @property minDurationMs Shortest event worth keeping. Raise it to drop brief jolts, at the cost of
 * losing real quick stabs at the pedal; lower it and road noise surviving the low-pass becomes events.
 *
 * @property mergeGapMs How long both force and rate must stay low before an open event truly ends.
 * This is what keeps one sustained brake with a wobble in the middle from being reported as two.
 * Raise it and genuinely separate maneuvers merge into one event with an inflated duration; lower
 * it and single maneuvers fragment.
 *
 * @property minSpeedMps Speed below which nothing is detected (default ~15 km/h), rejecting parking
 * and the walk to the car, since auto-tracking starts recording before you drive off. The speed
 * compared is the *lower* of the GPS reading and an IMU-derived one (see
 * [minYawForImuSpeedRadPerS]), so a wandering fix cannot fake its way past it. Raise it to exclude
 * more low-speed maneuvering, at the cost of missing genuine harshness in town; lower it and
 * car-park maneuvers register, since tight radii produce real force at walking pace.
 *
 * @property minYawForImuSpeedRadPerS Yaw rate below which speed cannot be derived from the IMU. In
 * any turn lateral force is v·ω, so dividing the two gives speed with no GPS involved — the
 * cross-check that catches a fix claiming 20 m/s while the car shuffles around a car park. Below
 * this rate the division is by ~nothing and would explode, so GPS speed stands alone. Raise it and
 * the cross-check applies less often; lower it and near-straight driving yields wild estimates.
 *
 * @property maxGyroRadPerSec Total gyro magnitude above which samples are discarded as phone
 * handling. Picking up or re-seating the phone produces rotation that swamps any real cornering: a
 * hard U-turn stays well under 1 rad/s, handling a phone is several. Raise it and handling leaks
 * through as events; lower it and violent but genuine vehicle rotation gets discarded.
 *
 * @property lowPassHz Cutoff of the one-pole low-pass applied before thresholding. Vehicle dynamics
 * live below ~2 Hz; above that is road surface and mount vibration. Filtering here is also what
 * makes differentiating for jerk viable at all — the derivative of a raw 50 Hz signal is noise.
 * Raise it and more road noise reaches the detector, inflating jerk and producing false events;
 * lower it and real maneuvers are blunted, so peaks read low and events are missed.
 *
 * @property maxSampleAgeNanos How stale an orientation or gyro reading may be before a sample is
 * skipped. Guards against a sensor dropping out mid-ride and its last reading being reused
 * indefinitely. Raise it and a dead sensor keeps supplying stale values — for orientation that leaks
 * gravity into the horizontal plane as g·sin θ; lower it past the sensor's own interval and samples
 * are discarded wholesale.
 *
 * @property maxFixAccuracyMeters Worst reported GPS accuracy still trusted. Stretches bracketed by a
 * fix the receiver itself calls poor are skipped entirely: no events beats events invented from a
 * position that isn't real. Raise it and detection continues over dubious positions; lower it and
 * more of a ride is suppressed, with tunnels and urban canyons dropping out.
 *
 * @property alignmentMinSpeedMps Minimum speed for a fix to contribute to forward-axis calibration
 * (default ~29 km/h). Calibration recovers the vehicle's forward direction in device coordinates,
 * which is what frees heading from GPS; only fast, straight, well-fixed driving contributes, because
 * that is the only place GPS heading is worth believing. Raise it for fewer but cleaner samples,
 * risking rides that never calibrate; lower it and unreliable low-speed headings contaminate the
 * axis — an error there misfiles force into the wrong direction rather than losing it.
 *
 * @property alignmentMaxTurnDeg Largest heading change between consecutive fixes that still counts
 * as straight. GPS heading lags through a corner, so sampling mid-turn would bias the estimated
 * axis. Raise it to gather samples faster, at the cost of that lag skewing the result; lower it and
 * calibration may never collect enough samples on a winding route.
 *
 * @property alignmentMinSamples How many qualifying fixes are needed before the estimated axis is
 * trusted at all. Raise it and more rides fall back to GPS heading, which is meaningless at low
 * speed and wrong when a fix jumps; lower it and a handful of fixes can set the axis for a whole ride.
 *
 * @property alignmentMinCoherence How strongly the calibration samples must agree, from 0 to 1
 * (default ≈37° mean scatter). Measured as the summed vector's length over the sample count: unit
 * vectors that agree sum to nearly the count, ones that scatter fall short. Its job is catching the
 * phone having been moved or re-mounted mid-ride, since the axis is then no longer a single
 * constant. The bar is deliberately forgiving: detection projects in the device frame, where only
 * the *mean* axis matters and per-sample scatter — mostly the rotation vector's magnetometer yaw
 * wobbling against GPS course — averages out to a degree or two over hundreds of samples. Raise it
 * and magnetically noisy but perfectly usable rides lose their axis (and with it all
 * accelerometer-based detection); lower it and a genuinely re-mounted phone's meaningless average
 * gets used as the vehicle's forward direction.
 */
data class RideEventConfig(
    val braking: DirectionThresholds = DirectionThresholds(0.9, 0.65, 0.25, 0.45),
    val acceleration: DirectionThresholds = DirectionThresholds(0.7, 0.5, 0.25, 0.32),
    val cornering: DirectionThresholds = DirectionThresholds(0.8, 0.65, 0.30, null),
    val jerkBaselineMs: Long = 100,
    val minDurationMs: Long = 250,
    val mergeGapMs: Long = 500,
    val minSpeedMps: Double = 4.0,
    val minYawForImuSpeedRadPerS: Double = 0.15,
    val maxGyroRadPerSec: Double = 2.5,
    val lowPassHz: Double = 2.0,
    val maxSampleAgeNanos: Long = 1_000_000_000,
    val maxFixAccuracyMeters: Double = 30.0,
    val alignmentMinSpeedMps: Double = 8.0,
    val alignmentMaxTurnDeg: Double = 5.0,
    val alignmentMinSamples: Int = 20,
    val alignmentMinCoherence: Double = 0.80,
)
