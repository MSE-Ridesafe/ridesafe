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
 * The whole method rests on removing gravity without ever trusting the magnetometer. The rotation
 * vector's vertical row gives world-up in device coordinates — exact for gravity (so the result is
 * slope-proof with no road-plane estimation) and immune to yaw error, which is the one component
 * fused from the magnetometer and inside a car is wrong by tens of degrees and varying. The
 * horizontal remainder is projected onto the vehicle's calibrated forward axis *in the device
 * frame*, splitting it into longitudinal (braking/acceleration) and lateral (cornering) with the
 * world frame never entered. GPS course is used only to calibrate that axis once per ride, where
 * yaw wobble merely averages out of the mean instead of misfiling force per sample.
 *
 * What counts as harsh is then judged on how fast the force builds rather than how large it gets —
 * see [RideEventConfig] — with a magnitude path for maneuvers that are hard however smoothly they
 * were started. Differentiating is only viable because the low-pass runs first: the derivative of a
 * raw 50 Hz signal is noise.
 *
 * Returns empty when the ride lacks the accelerometer, rotation vector or GPS the method needs, and
 * likewise when no forward axis could be calibrated — a missing sensor or an unknowable axis means
 * no score, never a guessed one.
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
