package de.uhi.enia.ridesafe.recording

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ride being recorded right now. [startedElapsedNanos] is its start on the
 * [SystemClock.elapsedRealtimeNanos] clock, so a reader can render the live duration from it alone,
 * without asking the engine anything.
 */
data class RunningRide(
    val startedElapsedNanos: Long,
    val vehicleId: Long?,
)

/**
 * What became of a ride that just ended, so the car screen can say so (TRK-07). [atElapsedMs] dates
 * the outcome: a reader that comes back hours later should not report it as news.
 */
sealed interface RideOutcome {
    val lengthMs: Long
    val atElapsedMs: Long

    /** Logged, and [rideId] is still there to be deleted if the driver decides against it. */
    data class Saved(
        override val lengthMs: Long,
        val rideId: Long,
        override val atElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) : RideOutcome

    /** Below the minimum ride length, so it was dropped instead of logged (TRK-10). */
    data class TooShort(
        override val lengthMs: Long,
        override val atElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) : RideOutcome
}

/**
 * Whether a ride is recording right now, and how the last one ended — for surfaces outside the
 * recording engine: the floating status bar, the Home record button, the Logbook, the car screen.
 *
 * Written by [RideRecordingEngine] as sessions start and stop, so it follows the ride rather than
 * the service: a car that reconnects within the grace (TRK-09) keeps its original start, and
 * [running] only clears once the ride is really finalized.
 *
 * In-process only, on purpose: recording lives in a foreground service, so while a ride runs this
 * process is alive and the value is current. A process death takes the ride with it — recovery
 * finalizes it on next app start (NFR-06) — and null is then the honest answer.
 */
object RecordingStatus {
    private val _running = MutableStateFlow<RunningRide?>(null)

    /** The ride in progress, or null when nothing is recording. */
    val running: StateFlow<RunningRide?> = _running.asStateFlow()

    private val _outcome = MutableStateFlow<RideOutcome?>(null)

    /** How the last ride ended, until someone [consumeOutcome]s it. Null after a ride the driver threw away: they know. */
    val outcome: StateFlow<RideOutcome?> = _outcome.asStateFlow()

    internal fun onStarted(
        startedElapsedNanos: Long,
        vehicleId: Long?,
    ) {
        _running.value = RunningRide(startedElapsedNanos, vehicleId)
        _outcome.value = null // a new ride supersedes whatever the last one did
    }

    internal fun onStopped() {
        _running.value = null
    }

    internal fun onFinished(outcome: RideOutcome?) {
        _outcome.value = outcome
    }

    /** Reported to the driver; don't report it again. */
    fun consumeOutcome() {
        _outcome.value = null
    }
}
