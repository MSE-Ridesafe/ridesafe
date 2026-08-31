package de.uhi.enia.ridesafe.analysis.event

/**
 * Rate of rise of a signal, in units per second, measured across a fixed time baseline.
 *
 * The baseline is the whole point. Differencing adjacent 50 Hz samples divides by 0.02 s, which
 * turns even 0.003 g of residual ripple into 0.15 g/s — the same range as the genuine jerk of smooth
 * driving, so the measurement would be mostly noise. Differencing across ~100 ms cuts that by five
 * while still resolving events that last several hundred ms.
 *
 * Only rises are reported; a falling signal returns zero, since easing off a brake or unwinding a
 * corner isn't harsh. Returns zero until the buffer spans at least half the baseline, so the first
 * samples of a ride can't divide a small change by a tiny dt and invent a spike.
 *
 * Single-use and single-threaded: one instance per analysis, driven by one coroutine. Concurrent
 * rides each get their own, which is what keeps parallel analysis safe.
 */
internal class RateTracker(
    private val baselineNanos: Long,
) {
    private val times = LongArray(CAPACITY)
    private val values = DoubleArray(CAPACITY)
    private var head = 0 // index of the oldest retained entry
    private var size = 0

    /** Forget the buffered history — for a signal whose basis just changed, not merely its value. */
    fun clear() {
        size = 0
        head = 0
    }

    fun update(
        nanos: Long,
        value: Double,
    ): Double {
        val tail = (head + size) % CAPACITY
        times[tail] = nanos
        values[tail] = value
        if (size < CAPACITY) size++ else head = (head + 1) % CAPACITY

        // Drop entries older than the baseline, keeping the one that straddles it as the reference.
        while (size > 2 && nanos - times[(head + 1) % CAPACITY] >= baselineNanos) {
            head = (head + 1) % CAPACITY
            size--
        }
        if (size < 2) return 0.0

        val dt = (nanos - times[head]) / 1e9
        if (dt < baselineNanos / 2e9) return 0.0
        return ((value - values[head]) / dt).coerceAtLeast(0.0)
    }

    private companion object {
        // ~1.3 s at 50 Hz — ample for a 100 ms baseline, which evicts long before this fills.
        const val CAPACITY = 64
    }
}
