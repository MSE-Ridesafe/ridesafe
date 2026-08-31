package de.uhi.enia.ridesafe.recording

/**
 * The seam between auto-tracking and ride recording. Auto-tracking decides *when* a trip
 * starts/ends and *which* vehicle it belongs to; the recorder decides what to do about it
 * (record GPS, sample sensors, persist a ride). [vehicleId] is null when the trip is in an
 * unmapped vehicle (ANY mode); the recording layer assigns it later. The recording layer
 * plugs in by setting [de.uhi.enia.ridesafe.recording.trigger.AutoTracking.recorder].
 */
interface RideRecorder {
    fun onTripStart(vehicleId: Long?)

    fun onTripEnd()
}
