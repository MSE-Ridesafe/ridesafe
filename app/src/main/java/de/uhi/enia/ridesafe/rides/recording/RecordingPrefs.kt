package de.uhi.enia.ridesafe.rides.recording

import de.uhi.enia.ridesafe.util.EnumPref

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

/** Persists [ReconnectGrace] (see [EnumPref]). */
object ReconnectGracePrefs : EnumPref<ReconnectGrace>("reconnect_grace", ReconnectGrace.entries, { ReconnectGrace.MIN_1 })

/** Persists [MinRideLength] (see [EnumPref]). */
object MinRideLengthPrefs : EnumPref<MinRideLength>("min_ride_length", MinRideLength.entries, { MinRideLength.SEC_30 })
