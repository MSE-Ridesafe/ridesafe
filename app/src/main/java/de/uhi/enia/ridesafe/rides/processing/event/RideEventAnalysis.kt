package de.uhi.enia.ridesafe.rides.processing.event

import android.content.Context
import android.util.Log
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.rides.processing.TrackFilter
import de.uhi.enia.ridesafe.rides.processing.kalmanFilterLocations
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import de.uhi.enia.ridesafe.rides.recording.RideSample
import de.uhi.enia.ridesafe.rides.recording.RideSamples
import de.uhi.enia.ridesafe.rides.recording.forEachSampleInTimeOrder
import de.uhi.enia.ridesafe.rides.recording.ridesDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bumped whenever detection changes in a way that invalidates stored events. Rides stamped with an
 * older value are re-analyzed on next launch, which is the whole re-tuning workflow: change the
 * detector, bump this, run the app.
 *
 * v2: detection threshold dropped from 0.20 g to 0.10 g, where real-world harsh driving actually
 * sits — 0.20 g was high enough that ordinary rides logged nothing at all.
 * v3: trigger moved from force to rate of force (jerk), with a magnitude path retained for
 * maneuvers that are hard however smoothly they were started.
 * v4: both halves of that AND raised to real-driving levels — jerk 0.6 → 1.0 g/s and the peak floor
 * 0.10 → 0.25 g. At the old values ordinary braking cleared both and nearly every stop was an event.
 * v5: heading now comes from the phone's orientation and a calibrated vehicle forward axis rather
 * than per-sample GPS, the speed gate reads Doppler instead of position-derived speed, and poorly
 * fixed stretches are skipped — all three so a wandering GPS can't manufacture events.
 * v6: the high-force bypass no longer applies to cornering, where v²/r geometry made every tight
 * low-speed turn an event regardless of how gently it was driven.
 * v7: thresholds are per-direction (an engine can't pull what brakes can), and the speed gate now
 * takes the lower of GPS and an IMU-derived speed so a wandering fix can't fake its way past it.
 * v8: analysis streams the file rather than materialising it, so every sensor is read at the full
 * recorded rate. Detection itself is unchanged; the version moves only because the orientation and
 * gyro thinning v7 shipped with is gone, which can shift a borderline event by a hair.
 */
const val ANALYZER_VERSION = 8

/**
 * Analyze one recorded ride (ANL-01): read its sample file and detect its driving events, tagged
 * with the ride's id and ready to persist. Null means the ride could not be read at all and should
 * be retried later; an empty list is a real result — a ride with nothing harsh in it.
 *
 * Two streaming passes, because the vehicle's forward axis is a whole-ride statistic and nothing can
 * be split into longitudinal and lateral until it is known. Neither pass materialises the ride: what
 * is held is the reorder window, the filter state and a second of acceleration, so memory is flat in
 * ride length. Every sensor is read at the recorded 50 Hz — nothing is thinned or skipped.
 *
 * Safe to run for several rides at once. Every piece of state involved — the filter, the estimator,
 * the detector and their accumulators — is created per call and touched by one coroutine, and the
 * only things shared are immutable: the config, the constants, and the reader's JSON instance. That
 * is a property to preserve rather than assume: a cache added at file scope here would silently make
 * concurrent analysis wrong.
 *
 * ponytail: a ride recorded without a gyroscope or rotation vector also yields an empty list, so
 * "no events" currently conflates "clean" with "unscoreable". Harmless while this only feeds a
 * marker layer; when the safety score (ANL-01) lands it needs its own sensor-availability signal
 * rather than reading zero events as a perfect drive.
 */
suspend fun analyzeRide(
    appContext: Context,
    ride: Ride,
    config: RideEventConfig = RideEventConfig(),
): List<RideEvent>? =
    withContext(Dispatchers.IO) {
        val file = File(ridesDir(appContext), ride.sampleFile)
        if (!file.exists()) return@withContext null

        // Both passes are long uninterrupted loops over millions of samples, so cancellation has to
        // be checked by hand — a queued ride the user cancels would otherwise run to completion with
        // nobody waiting for the answer. Every few thousand samples is often enough to feel instant
        // and rare enough not to matter.
        val context = currentCoroutineContext()
        var sampled = 0L
        val cooperate = { if (++sampled % CANCELLATION_CHECK_SAMPLES == 0L) context.ensureActive() }

        // Pass one: the vehicle's forward axis, plus enough of a census to tell an unanalysable ride
        // from a clean one and to catch a device whose sensor clock disagrees with its GPS clock.
        val estimator = ForwardAxisEstimator(config)
        val filter = TrackFilter()
        var accelCount = 0L
        var firstAccel = Long.MAX_VALUE
        var lastAccel = Long.MIN_VALUE
        var firstFix = Long.MAX_VALUE
        var lastFix = Long.MIN_VALUE
        forEachSampleInTimeOrder(file) { sample ->
            cooperate()
            when (sample) {
                is LocationSample -> {
                    if (sample.t < firstFix) firstFix = sample.t
                    lastFix = sample.t
                    filter.update(sample, estimator::onFix)
                }

                is MotionSample -> {
                    if (sample.sensor == MotionSensor.ACCEL) {
                        accelCount++
                        if (sample.t < firstAccel) firstAccel = sample.t
                        lastAccel = sample.t
                    } else {
                        estimator.onMotion(sample)
                    }
                }
            }
        }
        filter.finish()
        if (accelCount == 0L) return@withContext emptyList()

        // Motion and GPS timestamps are both meant to be on the elapsed-realtime base, but a few
        // vendors stamp sensors differently. Streams that don't overlap at all mean the clocks
        // disagree, which would otherwise surface as a silently event-free ride.
        if (firstFix != Long.MAX_VALUE && (lastAccel < firstFix || lastFix < firstAccel)) {
            Log.w(TAG_EVENTS, "ride ${ride.id}: motion and GPS timestamps don't overlap; events unreliable")
        }

        // Pass two: detection, with the axis pass one recovered.
        val detector = StreamingDetector(estimator.result(), config, ride.startedElapsedNanos)
        val detectionFilter = TrackFilter()
        forEachSampleInTimeOrder(file) { sample ->
            cooperate()
            when (sample) {
                is LocationSample -> detectionFilter.update(sample, detector::onFix)
                is MotionSample -> detector.onMotion(sample)
            }
        }
        detectionFilter.finish()
        detector.finish().map { it.copy(rideId = ride.id) }
    }

private const val TAG_EVENTS = "RideEvents"

/** How often the analysis loops check for cancellation — ~every 80 ms of recorded driving. */
private const val CANCELLATION_CHECK_SAMPLES = 4096L

/**
 * Detect harsh braking, acceleration and cornering in one ride's samples (ANL-01).
 *
 * The whole method rests on getting out of the device's frame, which is arbitrary and can shift
 * mid-ride. Each acceleration sample is rotated into the world ENU frame using the recorded
 * rotation vector, and the vertical component is then discarded. That single step does three jobs:
 * it removes gravity *exactly* (gravity is world-vertical by definition, so it never touches the
 * horizontal components), it makes the result slope-proof with no road-plane estimation, and it
 * leaves the true horizontal acceleration. Projecting that onto the direction of travel splits it
 * into longitudinal (braking/acceleration) and lateral (cornering).
 *
 * Direction of travel comes from the Kalman-filtered track, not raw GPS bearing, which is noise
 * below walking pace.
 *
 * What counts as harsh is then judged on how fast the force builds rather than how large it gets —
 * see [RideEventConfig] — with a magnitude path for maneuvers that are hard however smoothly they
 * were started. Differentiating is only viable because the low-pass runs first: the derivative of a
 * raw 50 Hz signal is noise.
 *
 * Returns empty when the ride lacks the accelerometer, rotation vector or GPS the method needs — a
 * missing sensor means no score, never a guessed one.
 */
fun detectRideEvents(
    samples: RideSamples,
    rideStartElapsedNanos: Long,
    config: RideEventConfig = RideEventConfig(),
): List<RideEvent> {
    if (samples.accel.isEmpty() || samples.rotation.isEmpty() || samples.locations.size < 2) return emptyList()

    val fixes = kalmanFilterLocations(samples.locations)
    val forward = estimateForwardAxis(fixes, samples.rotation, config)
    val detector = StreamingDetector(forward, config, rideStartElapsedNanos)
    // The lists arrive already split per stream; the detector wants them interleaved in time order,
    // exactly as the file-streaming path delivers them.
    val merged = ArrayList<RideSample>(fixes.size + samples.accel.size + samples.gyro.size + samples.rotation.size)
    merged.addAll(fixes)
    merged.addAll(samples.accel)
    merged.addAll(samples.gyro)
    merged.addAll(samples.rotation)
    merged.sortWith { a, b -> a.t.compareTo(b.t) }
    for (sample in merged) {
        when (sample) {
            is LocationSample -> detector.onFix(sample)
            is MotionSample -> detector.onMotion(sample)
        }
    }
    return detector.finish()
}
