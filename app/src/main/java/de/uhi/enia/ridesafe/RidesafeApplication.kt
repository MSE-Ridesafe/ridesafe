package de.uhi.enia.ridesafe

import android.app.Application
import de.uhi.enia.ridesafe.tracking.AutoTrackPrefs
import de.uhi.enia.ridesafe.tracking.applyAutoTrackMode

/**
 * Re-arms activity recognition on process start (the Bluetooth ACL receiver is a manifest
 * receiver and needs no arming). [de.uhi.enia.ridesafe.tracking.BootReceiver] calls the same
 * path after a reboot. The recording layer will later set
 * [de.uhi.enia.ridesafe.tracking.AutoTracking.recorder] here.
 */
class RidesafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        applyAutoTrackMode(this, AutoTrackPrefs.get(this))
    }
}
