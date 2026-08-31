package de.uhi.enia.ridesafe.analysis.event

import de.uhi.enia.ridesafe.data.entity.RideEvent
import de.uhi.enia.ridesafe.data.entity.RideEventType

/**
 * Turns a stream of (force, rate) pairs into discrete events for one direction of travel.
 *
 * An event opens when the force builds fast enough ([DirectionThresholds.enterJerkGPerS]) *or* gets
 * high enough on its own ([DirectionThresholds.highPeakG], which is null for directions that have no
 * such bypass) *or* — for a sample fed as `armed` — merely clears [DirectionThresholds.minPeakG].
 * Armed means the sustained-Δv path has already established harshness from the car's own Doppler
 * speed (see StreamingDetector); demanding the usual entry evidence on top would re-ask a question
 * that is already answered, and would miss exactly the maneuvers that path exists for, where a
 * badly seated phone kept the accelerometer signal small. It stays open while either the rate is still elevated
 * ([DirectionThresholds.exitJerkGPerS]) or the force is still above
 * [DirectionThresholds.minPeakG] — that second term is what carries it through the steady middle of
 * a maneuver, where jerk is near zero by definition. It only truly ends once both have stayed low
 * for [RideEventConfig.mergeGapMs], the grace period that keeps one sustained brake from being
 * reported as a handful of separate ones.
 *
 * On close an event is kept only if it lasted at least [RideEventConfig.minDurationMs] and its peak
 * force cleared [DirectionThresholds.minPeakG] — the check that discards a sudden but trivial twitch.
 *
 * A braking or acceleration event must additionally be corroborated by the car itself: across the
 * event, GPS Doppler speed has to move in the claimed direction by at least
 * [RideEventConfig.dvAgreementFraction] of what the claimed force would produce (floored at
 * [RideEventConfig.dvAgreementFloorMps]). The accelerometer measures the phone, Doppler measures
 * the car, and when they disagree the phone moved in its mount — a loose phone lurching backwards
 * under a hard launch is indistinguishable from a harsh brake on the accelerometer alone. The
 * speeds compared are those of the fix before the event opened and the newest fix by the time it
 * closes, so the window spans the whole maneuver at fix granularity. Cornering is exempt: it moves
 * no speed, and its own corroboration is the gyro (see the cornering signal in StreamingDetector).
 *
 * Note which type owns which knob: the thresholds describing *this* direction live on
 * [DirectionThresholds], since braking, acceleration and cornering each need their own; the timing
 * rules that apply to every direction alike live on [RideEventConfig].
 *
 * Single-use and single-threaded: one instance per analysis, driven by one coroutine. Concurrent
 * rides each get their own, which is what keeps parallel analysis safe.
 */
internal class EventAccumulator(
    private val type: RideEventType,
    private val config: RideEventConfig,
    private val thresholds: DirectionThresholds,
    private val rideStartElapsedNanos: Long,
) {
    private val collected = mutableListOf<RideEvent>()
    private var open = false
    private var startNanos = 0L
    private var endNanos = 0L // last moment the maneuver still sustained
    private var closingSince: Long? = null
    private var peak = 0.0
    private var peakJerk = 0.0
    private var sum = 0.0
    private var count = 0
    private var peakSpeed = 0.0
    private var peakLat: Double? = null
    private var peakLon: Double? = null
    private var speedBeforeOpen = 0.0
    private var latestFixSpeed = 0.0

    fun feed(
        nanos: Long,
        magnitudeG: Double,
        jerkGPerS: Double,
        state: TrackState?,
        armed: Boolean = false,
    ) {
        // Tracked outside the open/closed logic on purpose: the fix that corroborates an event's
        // tail often arrives while the event is already coasting through its merge gap.
        if (state != null) latestFixSpeed = state.currentFixSpeedMps
        val sustains = jerkGPerS >= thresholds.exitJerkGPerS || magnitudeG >= thresholds.minPeakG
        if (open) {
            if (sustains) {
                closingSince = null
                extend(nanos, magnitudeG, jerkGPerS, state)
            } else {
                val since = closingSince ?: nanos.also { closingSince = it }
                if (nanos - since > config.mergeGapMs * 1_000_000) flush()
            }
        } else if (jerkGPerS >= thresholds.enterJerkGPerS ||
            (thresholds.highPeakG != null && magnitudeG >= thresholds.highPeakG) ||
            (armed && magnitudeG >= thresholds.minPeakG)
        ) {
            open = true
            startNanos = nanos
            closingSince = null
            peak = 0.0
            peakJerk = 0.0
            sum = 0.0
            count = 0
            // Opening requires an ungated sample, so bracketing fixes exist here by construction.
            speedBeforeOpen = state?.previousFixSpeedMps ?: latestFixSpeed
            extend(nanos, magnitudeG, jerkGPerS, state)
        }
    }

    private fun extend(
        nanos: Long,
        magnitudeG: Double,
        jerkGPerS: Double,
        state: TrackState?,
    ) {
        endNanos = nanos
        sum += magnitudeG
        count++
        if (jerkGPerS > peakJerk) peakJerk = jerkGPerS
        if (magnitudeG > peak) {
            peak = magnitudeG
            peakSpeed = state?.speedMps ?: 0.0
            peakLat = state?.lat
            peakLon = state?.lon
        }
    }

    /**
     * Whether the car's Doppler speed confirms this finished event's claimed direction and rough
     * size. Applied on the finished event like the peak floor, because only there are the average
     * force and duration known.
     */
    private fun corroborated(durationMs: Long): Boolean {
        if (type == RideEventType.CORNERING || count == 0) return true
        val dv = latestFixSpeed - speedBeforeOpen
        val needed =
            (config.dvAgreementFraction * (sum / count) * G * (durationMs / 1000.0))
                .coerceAtLeast(config.dvAgreementFloorMps)
        return if (type == RideEventType.BRAKING) dv <= -needed else dv >= needed
    }

    private fun flush() {
        val durationMs = (endNanos - startNanos) / 1_000_000
        // The peak-force floor is applied here, on the finished event, not per sample: jerk peaks at
        // a maneuver's onset while the force is still near zero, so a per-sample gate would have
        // thrown away the very spike that opened it.
        if (durationMs >= config.minDurationMs && count > 0 && peak >= thresholds.minPeakG && corroborated(durationMs)) {
            collected.add(
                RideEvent(
                    type = type,
                    // Offset from the ride's start, so an event reads on its own; the sample stream's
                    // monotonic base comes from Ride.startedElapsedNanos.
                    startOffsetMs = (startNanos - rideStartElapsedNanos) / 1_000_000,
                    durationMs = durationMs,
                    peakG = peak,
                    peakJerkGPerS = peakJerk,
                    avgG = sum / count,
                    speedMps = peakSpeed,
                    lat = peakLat,
                    lon = peakLon,
                ),
            )
        }
        open = false
        closingSince = null
    }

    fun finish(): List<RideEvent> {
        if (open) flush()
        return collected
    }
}
