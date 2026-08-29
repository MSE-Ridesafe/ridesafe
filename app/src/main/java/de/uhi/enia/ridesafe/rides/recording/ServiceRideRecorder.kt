package de.uhi.enia.ridesafe.rides.recording

import android.content.Context

/**
 * The [RideRecorder] wired into [de.uhi.enia.ridesafe.rides.trigger.AutoTracking.recorder]: turns trip start/end from the
 * auto-tracking trigger into start/stop commands for [RideRecordingService], so recording runs
 * in a foreground service (TRK-05) instead of the trigger's short-lived broadcast process.
 *
 * Both connect and disconnect arrive inside a Bluetooth-broadcast wake, which grants the window
 * to start a foreground service from the background. A disconnect is a wait-and-see end: the
 * reconnect grace (TRK-09) applies, unlike a stop the user asked for on the car screen.
 */
class ServiceRideRecorder(
    private val context: Context,
) : RideRecorder {
    override fun onTripStart(vehicleId: Long?) {
        RideRecordingService.start(context, vehicleId)
    }

    override fun onTripEnd() {
        RideRecordingService.stop(context)
    }
}
