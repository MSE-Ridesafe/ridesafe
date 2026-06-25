package de.uhi.enia.ridesafe.tracking

import android.util.Log

/**
 * The seam between auto-tracking and ride recording. Auto-tracking decides *when* a trip
 * starts/ends and *which* vehicle it belongs to; the recorder decides what to do about it
 * (record GPS, sample sensors, persist a ride — none of that exists yet). [vehicleId] is
 * null when the trip is in an unmapped vehicle (ANY mode); the recording layer assigns it
 * later. The recording layer plugs in by setting [AutoTracking.recorder].
 */
interface RideRecorder {
    fun onTripStart(vehicleId: Long?)

    fun onTripEnd()
}

/** Default recorder until the real one is wired in: just logs, so triggers are visible in Logcat. */
object LoggingRecorder : RideRecorder {
    private const val TAG = "AutoTracking"

    override fun onTripStart(vehicleId: Long?) {
        Log.i(TAG, "trip start (vehicle=$vehicleId)")
    }

    override fun onTripEnd() {
        Log.i(TAG, "trip end")
    }
}
