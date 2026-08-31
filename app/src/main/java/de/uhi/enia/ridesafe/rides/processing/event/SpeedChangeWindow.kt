package de.uhi.enia.ridesafe.rides.processing.event

/**
 * Change of a signal across a trailing time window — for the detector, how much Doppler speed the
 * car gained or lost within the last few seconds.
 *
 * The window is the point: per-interval Doppler slope is a 1 Hz staircase whose steps read as
 * enormous fake jerk, and a single-interval speed jump is indistinguishable from a launch blip or
 * a reacquisition glitch. Asking "did speed move this far within the whole window" is immune to
 * both, at the cost of only arming a couple of seconds into a maneuver — acceptable for the
 * *sustained* harshness this feeds, which by definition lasts longer than that.
 *
 * Returns zero until the buffer actually spans most of the window, so a fresh start (or the reset
 * after a GPS gap — see [clear]) cannot compare across missing time and invent a change.
 *
 * Single-use and single-threaded: one instance per analysis, driven by one coroutine.
 */
internal class SpeedChangeWindow(
    windowMs: Long,
) {
    private val windowNanos = windowMs * 1_000_000
    private val minCoverNanos = windowNanos * 9 / 10
    private val times = LongArray(CAPACITY)
    private val values = DoubleArray(CAPACITY)
    private var head = 0
    private var size = 0

    /** Forget everything — called on a GPS gap, whose two sides must never be compared. */
    fun clear() {
        size = 0
    }

    fun update(
        nanos: Long,
        value: Double,
    ): Double {
        val tail = (head + size) % CAPACITY
        times[tail] = nanos
        values[tail] = value
        if (size < CAPACITY) size++ else head = (head + 1) % CAPACITY

        // Drop entries older than the window, keeping the one that straddles it as the reference.
        while (size > 2 && nanos - times[(head + 1) % CAPACITY] >= windowNanos) {
            head = (head + 1) % CAPACITY
            size--
        }
        if (size < 2 || nanos - times[head] < minCoverNanos) return 0.0
        return value - values[head]
    }

    private companion object {
        // ~4.3 s at 50 Hz for a 3 s window; eviction keeps it from mattering at higher rates.
        const val CAPACITY = 512
    }
}
