package de.uhi.enia.ridesafe.rides.trigger

import android.content.Context
import de.uhi.enia.ridesafe.util.EnumPref

/** SET-06: how aggressively rides are auto-recorded. */
enum class AutoTrackMode {
    /** No automatic recording. */
    OFF,

    /** Only record when connected to a vehicle's mapped Bluetooth device (TRK-08). */
    PAIRED_ONLY,

    /** Record any detected car trip; assign the mapped vehicle if connected, else leave unassigned. */
    ANY,
}

/**
 * Persists the auto-tracking mode (see [EnumPref]). Off until the user asks for it (SET-06):
 * enabling a mode is what triggers the first permission request, so nothing is recorded before the
 * user has agreed to it.
 */
object AutoTrackPrefs : EnumPref<AutoTrackMode>("auto_track_mode", AutoTrackMode.entries, { AutoTrackMode.OFF })

/**
 * Persist [mode] and (re)arm the matching detectors. Called when the user changes the
 * setting and on process start ([de.uhi.enia.ridesafe.RidesafeApplication]).
 *
 * Bluetooth connect/disconnect is handled by the always-on manifest
 * [BluetoothConnectionReceiver] (which the engine gates on the mode), so only activity
 * recognition — the heavier Play-Services path used by ANY mode — needs arming here.
 */
fun applyAutoTrackMode(
    context: Context,
    mode: AutoTrackMode,
) {
    AutoTrackPrefs.set(context, mode)
    if (mode == AutoTrackMode.ANY) {
        ActivityRecognitionTracker.register(context)
    } else {
        ActivityRecognitionTracker.unregister(context)
    }
}
