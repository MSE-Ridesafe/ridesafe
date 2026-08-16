package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.rides.processing.kalmanFilterLocations
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.RideSample
import de.uhi.enia.ridesafe.rides.recording.RideSamples

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
