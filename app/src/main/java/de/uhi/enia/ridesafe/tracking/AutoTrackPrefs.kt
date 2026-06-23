package de.uhi.enia.ridesafe.tracking

import android.content.Context
import androidx.core.content.edit

/** SET-06: how aggressively rides are auto-recorded. */
enum class AutoTrackMode {
    /** No automatic recording. */
    OFF,

    /** Only record when connected to a vehicle's mapped Bluetooth device (TRK-08). */
    PAIRED_ONLY,

    /** Record any detected car trip; assign the mapped vehicle if connected, else leave unassigned. */
    ANY,
}

/** Persists the auto-tracking mode in the shared prefs file used across the app (cf. [de.uhi.enia.ridesafe.util.UnitPrefs]). */
object AutoTrackPrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_MODE = "auto_track_mode"

    fun get(context: Context): AutoTrackMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_MODE, AutoTrackMode.PAIRED_ONLY.name)
        return try {
            AutoTrackMode.valueOf(name ?: AutoTrackMode.PAIRED_ONLY.name)
        } catch (e: Exception) {
            AutoTrackMode.PAIRED_ONLY
        }
    }

    fun set(
        context: Context,
        value: AutoTrackMode,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_MODE, value.name) }
    }
}

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
