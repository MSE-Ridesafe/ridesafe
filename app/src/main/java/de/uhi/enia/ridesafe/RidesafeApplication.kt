package de.uhi.enia.ridesafe

import android.app.Application
import de.uhi.enia.ridesafe.rides.recording.RideRecordingEngine
import de.uhi.enia.ridesafe.rides.recording.ServiceRideRecorder
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.rides.trigger.AutoTracking
import de.uhi.enia.ridesafe.rides.trigger.applyAutoTrackMode
import de.uhi.enia.ridesafe.util.localeAppContext

/**
 * Re-arms activity recognition on process start (the Bluetooth ACL receiver is a manifest
 * receiver and needs no arming). [de.uhi.enia.ridesafe.rides.trigger.BootReceiver] calls the same
 * path after a reboot, and the same path now also wires ride recording to the trigger.
 */
class RidesafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Lets the context-free formatters ask LocaleManager for the device locale (SET-07/08).
        localeAppContext = this
        applyAutoTrackMode(this, AutoTrackPrefs.get(this))
        // Wire the auto-tracking trigger to ride recording: a mapped-device connect/disconnect now
        // starts/stops a foreground recording service (TRK-01/TRK-05).
        AutoTracking.recorder = ServiceRideRecorder(this)
        // Finalize any ride left open by a crash/kill (NFR-06).
        RideRecordingEngine(this).recoverDanglingAsync()
    }
}
