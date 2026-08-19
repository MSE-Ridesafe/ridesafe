package de.uhi.enia.ridesafe.rides.recording

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * SET-10: how long a ride keeps recording after the car disconnects, waiting for it to come back
 * (TRK-09). Long enough covers a car that cycles its infotainment while the driver gets out; [OFF]
 * ends the ride the moment the connection drops.
 *
 * The choices are a short list rather than a free number: the value only has to outlast a car's
 * shutdown-and-back cycle, and the tail is buffered in memory while it runs (see [RideTail]), so
 * arbitrarily long graces are not worth offering.
 */
enum class ReconnectGrace(
    val millis: Long,
) {
    OFF(0),
    SEC_30(30_000),
    MIN_1(60_000),
    MIN_2(120_000),
    MIN_5(300_000),
}

/**
 * SET-11: how long a recording has to be before it counts as a ride (TRK-10). Anything shorter is
 * deleted instead of logged — a Bluetooth blip, moving the car inside the driveway, a connect
 * caught as a passenger. [OFF] keeps every recording, however brief.
 */
enum class MinRideLength(
    val millis: Long,
) {
    OFF(0),
    SEC_15(15_000),
    SEC_30(30_000),
    SEC_60(60_000),
    MIN_2(120_000),
}

/** Persists [ReconnectGrace] in the shared prefs file used across the app (cf. [de.uhi.enia.ridesafe.util.UnitPrefs]). */
object ReconnectGracePrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_GRACE = "reconnect_grace"
    private val DEFAULT = ReconnectGrace.MIN_1

    private var cached by mutableStateOf<ReconnectGrace?>(null)

    /** Backed by snapshot state like [de.uhi.enia.ridesafe.util.UnitPrefs] — readers stay current. */
    fun get(context: Context): ReconnectGrace = cached ?: read(context).also { cached = it }

    fun set(
        context: Context,
        value: ReconnectGrace,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_GRACE, value.name) }
        cached = value
    }

    private fun read(context: Context): ReconnectGrace {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_GRACE, DEFAULT.name)
        return try {
            ReconnectGrace.valueOf(name ?: DEFAULT.name)
        } catch (_: Exception) {
            DEFAULT
        }
    }
}

/** Persists [MinRideLength]; see [ReconnectGracePrefs] for the shared idiom. */
object MinRideLengthPrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_MIN_LENGTH = "min_ride_length"
    private val DEFAULT = MinRideLength.SEC_30

    private var cached by mutableStateOf<MinRideLength?>(null)

    fun get(context: Context): MinRideLength = cached ?: read(context).also { cached = it }

    fun set(
        context: Context,
        value: MinRideLength,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_MIN_LENGTH, value.name) }
        cached = value
    }

    private fun read(context: Context): MinRideLength {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_MIN_LENGTH, DEFAULT.name)
        return try {
            MinRideLength.valueOf(name ?: DEFAULT.name)
        } catch (_: Exception) {
            DEFAULT
        }
    }
}
