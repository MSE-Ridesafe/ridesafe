package de.uhi.enia.ridesafe.tracking

/**
 * Turns detector events (Bluetooth connect/disconnect from [BluetoothConnectionReceiver],
 * IN_VEHICLE transitions from [ActivityTransitionReceiver]) into trip start/end calls on a
 * [RideRecorder]. Pure Kotlin (no Android imports) so the mode-gating, de-duplication and
 * ANY-mode assignment are unit-testable — see AutoTrackEngineTest.
 *
 * State is process-global (held by [AutoTracking]); the detectors run in-process and call
 * these methods. [present] is the set of currently-connected mapped Bluetooth addresses,
 * so ANY mode can attribute an activity-detected trip to a vehicle.
 */
class AutoTrackEngine(
    private val recorder: () -> RideRecorder,
) {
    private var active = false
    private var activeVehicleId: Long? = null
    private val present = mutableSetOf<String>()

    /** A mapped Bluetooth device connected (CDM presence). Starts a trip unless tracking is OFF. */
    @Synchronized
    fun deviceConnected(
        mode: AutoTrackMode,
        address: String,
        vehicleId: Long?,
    ) {
        present += address
        if (mode == AutoTrackMode.OFF) return
        // A mapped connect starts immediately in both PAIRED_ONLY and ANY (better than
        // waiting for activity recognition); a later IN_VEHICLE enter is then de-duped.
        start(vehicleId)
    }

    /** A mapped device disconnected. Ends the trip once no mapped device is connected. */
    @Synchronized
    fun deviceDisconnected(address: String) {
        present -= address
        // ponytail: stop when the last mapped device drops; a momentary BT blip therefore
        // stops+restarts a trip — debounce belongs in the recording layer (TRK-06), not here.
        if (present.isEmpty()) stop()
    }

    /** Activity recognition reported IN_VEHICLE enter (ANY mode only); attribute to a present car. */
    @Synchronized
    fun activityVehicleStart(
        mode: AutoTrackMode,
        mapping: Map<String, Long>,
    ) {
        if (mode != AutoTrackMode.ANY) return
        start(present.firstNotNullOfOrNull { mapping[it] })
    }

    /** Activity recognition reported IN_VEHICLE exit (ANY mode only). */
    @Synchronized
    fun activityVehicleEnd(mode: AutoTrackMode) {
        if (mode != AutoTrackMode.ANY) return
        stop()
    }

    private fun start(vehicleId: Long?) {
        if (active) return
        active = true
        activeVehicleId = vehicleId
        recorder().onTripStart(vehicleId)
    }

    private fun stop() {
        if (!active) return
        active = false
        activeVehicleId = null
        recorder().onTripEnd()
    }
}

/**
 * Process-global auto-tracking state and the plugging point for ride recording. The
 * recording layer (later) assigns [recorder] in Application.onCreate; until then trips are
 * logged by [LoggingRecorder].
 */
object AutoTracking {
    @Volatile
    var recorder: RideRecorder = LoggingRecorder

    val engine = AutoTrackEngine { recorder }
}
