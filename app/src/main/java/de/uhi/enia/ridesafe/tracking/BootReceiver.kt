package de.uhi.enia.ridesafe.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms auto-tracking after a device reboot. Without this, the process would only start
 * (and [de.uhi.enia.ridesafe.RidesafeApplication.onCreate] re-arm) once the user next opens
 * the app — leaving tracking dormant in between. Receiving BOOT_COMPLETED launches the
 * process and re-establishes CDM observation / activity recognition for the saved mode.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Idempotent with the Application.onCreate that also ran as the process started.
        applyAutoTrackMode(context.applicationContext, AutoTrackPrefs.get(context))
    }
}
