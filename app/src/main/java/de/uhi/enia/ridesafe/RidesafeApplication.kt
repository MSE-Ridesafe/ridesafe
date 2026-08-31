package de.uhi.enia.ridesafe

import android.app.Application
import de.uhi.enia.ridesafe.core.format.localeAppContext
import de.uhi.enia.ridesafe.recording.RideRecorder
import de.uhi.enia.ridesafe.recording.RideRecordingEngine
import de.uhi.enia.ridesafe.recording.RideRecordingService
import de.uhi.enia.ridesafe.recording.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.recording.trigger.AutoTracking
import de.uhi.enia.ridesafe.recording.trigger.applyAutoTrackMode

/**
 * Re-arms activity recognition on process start (the Bluetooth ACL receiver is a manifest
 * receiver and needs no arming). [de.uhi.enia.ridesafe.recording.trigger.BootReceiver] calls the same
 * path after a reboot, and the same path now also wires ride recording to the trigger.
 */
class RidesafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Lets the context-free formatters ask LocaleManager for the device locale (SET-07/08).
        localeAppContext = this
        applyAutoTrackMode(this, AutoTrackPrefs.get(this))
        // Wire the auto-tracking trigger to ride recording: a mapped-device connect/disconnect
        // starts/stops a foreground recording service (TRK-05). Both arrive inside a
        // Bluetooth-broadcast wake, which grants the window to start a foreground service from the
        // background. A disconnect is a wait-and-see end: the reconnect grace (TRK-09) applies,
        // unlike a stop the user asked for on the car screen.
        AutoTracking.recorder =
            object : RideRecorder {
                override fun onTripStart(vehicleId: Long?) {
                    RideRecordingService.start(this@RidesafeApplication, vehicleId)
                }

                override fun onTripEnd() {
                    RideRecordingService.stop(this@RidesafeApplication)
                }
            }
        // Finalize any ride left open by a crash/kill (NFR-06).
        RideRecordingEngine(this).recoverDanglingAsync()
    }
}
