package de.uhi.enia.ridesafe.recording

import de.uhi.enia.ridesafe.data.file.LocationSample
import de.uhi.enia.ridesafe.data.file.RideSample

/**
 * Record-time ride statistics, fed one GPS fix at a time. Captures the endpoints (start/end
 * position, DR-RID) and the fastest reported fix. Distance and average speed are deferred to the
 * analysis pass over the sample file (ANL-02), so they're left for that pass to fill, not here.
 */
class RideStats {
    var startFix: LocationSample? = null
        private set
    var endFix: LocationSample? = null
        private set
    var maxSpeedMps = 0.0
        private set

    fun add(loc: LocationSample) {
        if (startFix == null) startFix = loc
        endFix = loc
        if (loc.speed > maxSpeedMps) maxSpeedMps = loc.speed.toDouble()
    }
}

/** Build [RideStats] from a recorded location stream — used when recovering a ride from its file. */
fun rideStatsOf(locations: List<LocationSample>): RideStats = RideStats().apply { locations.forEach(::add) }

/**
 * Holds back what gets recorded after a trip ended, until the reconnect grace decides its fate
 * (TRK-09). Cars shut their infotainment down when the driver gets out and bring it back seconds
 * later, so a disconnect is only provisionally the end of the ride: recording carries on, but into
 * this buffer. The same car reconnecting ([rejoin]) releases the buffer into the ride, which
 * continues as if nothing happened; the grace running out drops it, leaving the ride ending exactly
 * at [begin]'s mark.
 *
 * What is held is decided by a sample's own timestamp, not by when it turned up: motion arrives
 * batched out of the sensor FIFO seconds late, and a sample taken *before* the mark belongs to the
 * ride however late it lands.
 *
 * ponytail: the buffer is in memory, so it costs ~1 MB per grace minute at the default rates —
 * fine for a 1-minute grace; spill to a side file if the grace ever grows to many minutes.
 * [begin]/[rejoin] come from the engine's command loop, [accept]/[drain] from its writer.
 */
class RideTail {
    @Volatile
    private var markNanos = Long.MAX_VALUE
    private val held = ArrayList<RideSample>()

    /** True once the ride has provisionally ended and samples are being held back. */
    val isHolding: Boolean
        get() = markNanos != Long.MAX_VALUE

    /** The ride provisionally ended at [markNanos] (elapsed-realtime base); start holding. */
    fun begin(markNanos: Long) {
        this.markNanos = markNanos
    }

    /** The same vehicle reconnected in time: everything held belongs to the ride after all. */
    fun rejoin() {
        markNanos = Long.MAX_VALUE
    }

    /** Passes [sample] to [write], or holds it back when it was recorded past the mark. */
    fun accept(
        sample: RideSample,
        write: (RideSample) -> Unit,
    ) {
        if (sample.t > markNanos) {
            held += sample
            return
        }
        drain(write)
        write(sample)
    }

    /** Writes out what was held — a no-op while still holding, since it may yet be dropped. */
    fun drain(write: (RideSample) -> Unit) {
        if (isHolding || held.isEmpty()) return
        held.forEach(write)
        held.clear()
    }
}
