package de.uhi.enia.ridesafe.rides.processing

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.rides.processing.event.ForwardAxisEstimator
import de.uhi.enia.ridesafe.rides.processing.event.RideEventConfig
import de.uhi.enia.ridesafe.rides.processing.event.StreamingDetector
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import de.uhi.enia.ridesafe.rides.recording.trackDistanceMeters

/**
 * GPS processing (ANL-02): store the Kalman-filtered track as an RDP-simplified sidecar and persist
 * the distance/average speed off it. The raw NDJSON file stays the source of truth — this output is
 * derived and additive, so it can be regenerated at any time.
 *
 * No sink: the filtering itself belongs to the pass driver, which every stage in the pass shares,
 * so this stage only consumes the fixes the driver left on the context.
 *
 * [version] covers the filter *and* the simplification, since both decide what the sidecar holds —
 * which is why the stages downstream depend on this one: a track that filters differently splits
 * into different events.
 *
 * v2: the filter drops outliers instead of predicting through them, restarts after a GPS gap, and
 * discards uncorroborated runs — v1 tracks ended at the first bogus fix following an outage and
 * free-ran off the map from there, taking the distance with them.
 */
class RouteStage(
    private val db: RidesafeDatabase,
) : RideStage {
    override val id = "route"
    override val version = ROUTE_VERSION
    override val restorable = true

    override suspend fun finish(ctx: RideAnalysisContext) {
        val fixes = ctx.filteredFixes.orEmpty().map { it.fix }
        // Everything filtered out means no usable track, not a zero-length ride: leave the metrics
        // null and write no sidecar, so the map falls back to raw fixes. Still a definitive answer
        // for this version of the filter, so the ride is stamped rather than re-read every launch —
        // a better filter revisits it by bumping [version], which is what versioning is for.
        if (fixes.isEmpty()) {
            Log.i(TAG_ROUTE, "ride ${ctx.ride.id}: no usable track; no route or metrics stored")
            return
        }

        val distance = trackDistanceMeters(fixes)
        val durationSec = ctx.ride.endedAtEpochMs?.let { (it - ctx.ride.startedAtEpochMs) / 1000.0 } ?: 0.0
        val metrics = RideMetrics(distance, if (durationSec > 0) distance / durationSec else 0.0)

        writeProcessedRoute(
            processedRouteFile(ctx.appContext, ctx.ride),
            simplifyRoute(fixes.map { LatLng(it.lat, it.lon) }),
        )
        db.rideDao().setMetrics(ctx.ride.id, metrics.distanceMeters, metrics.avgSpeedMps)
        ctx.metrics = metrics
    }

    /** The sidecar is the output; metrics are already on the ride row. Missing file ⇒ derive again. */
    override suspend fun load(ctx: RideAnalysisContext): Boolean = readProcessedRoute(processedRouteFile(ctx.appContext, ctx.ride)) != null
}

/**
 * The vehicle's forward axis in the device frame, plus enough of a census to tell an unanalysable
 * ride from a clean one. Whole-ride statistic, and nothing can be split into longitudinal and
 * lateral without it — which is the sole reason detection needs a second pass over the file.
 *
 * ponytail: the axis is recomputed every time because it is never stored. Persisting it in a
 * sidecar next to the route would make a pure detector re-tune single-pass; worth doing if
 * threshold tuning starts feeling slow, and it needs its own staleness rule when it lands.
 */
class ForwardAxisStage(
    private val config: RideEventConfig = RideEventConfig(),
) : RideStage {
    override val id = "axis"
    override val version = 1
    override val dependsOn = listOf("route")

    private val estimator = ForwardAxisEstimator(config)
    private var accelCount = 0L
    private var firstAccel = Long.MAX_VALUE
    private var lastAccel = Long.MIN_VALUE
    private var firstFix = Long.MAX_VALUE
    private var lastFix = Long.MIN_VALUE

    override fun sink(ctx: RideAnalysisContext) =
        SampleSink { sample ->
            when (sample) {
                is LocationSample -> {
                    if (sample.t < firstFix) firstFix = sample.t
                    lastFix = sample.t
                    estimator.onFix(sample)
                }

                is MotionSample -> {
                    if (sample.sensor == MotionSensor.ACCEL) {
                        accelCount++
                        if (sample.t < firstAccel) firstAccel = sample.t
                        lastAccel = sample.t
                    } else {
                        estimator.onMotion(sample)
                    }
                }
            }
        }

    override suspend fun finish(ctx: RideAnalysisContext) {
        ctx.hasAccel = accelCount > 0
        ctx.forwardAxis = estimator.result()
        // Motion and GPS timestamps are both meant to be on the elapsed-realtime base, but a few
        // vendors stamp sensors differently. Streams that don't overlap at all mean the clocks
        // disagree, which would otherwise surface as a silently event-free ride.
        if (ctx.hasAccel && firstFix != Long.MAX_VALUE && (lastAccel < firstFix || lastFix < firstAccel)) {
            Log.w(TAG_EVENTS, "ride ${ctx.ride.id}: motion and GPS timestamps don't overlap; events unreliable")
        }
    }
}

/**
 * Driving-event detection (ANL-01): harsh braking, acceleration and cornering, tagged with the
 * ride's id. Streams the file, so every sensor is read at the recorded 50 Hz and memory stays flat
 * in ride length — what is held is the reorder window and a second of acceleration.
 *
 * A ride with no acceleration samples yields an empty list, same as before.
 *
 * ponytail: a ride recorded without a gyroscope or rotation vector also yields an empty list, so
 * "no events" currently conflates "clean" with "unscoreable". Harmless while this only feeds a
 * marker layer; when the safety score (ANL-01) lands it needs its own sensor-availability signal
 * rather than reading zero events as a perfect drive.
 *
 * v2: detection threshold dropped from 0.20 g to 0.10 g, where real-world harsh driving actually
 * sits — 0.20 g was high enough that ordinary rides logged nothing at all.
 * v3: trigger moved from force to rate of force (jerk), with a magnitude path retained for
 * maneuvers that are hard however smoothly they were started.
 * v4: both halves of that AND raised to real-driving levels — jerk 0.6 → 1.0 g/s and the peak floor
 * 0.10 → 0.25 g. At the old values ordinary braking cleared both and nearly every stop was an event.
 * v5: heading now comes from the phone's orientation and a calibrated vehicle forward axis rather
 * than per-sample GPS, the speed gate reads Doppler instead of position-derived speed, and poorly
 * fixed stretches are skipped — all three so a wandering GPS can't manufacture events.
 * v6: the high-force bypass no longer applies to cornering, where v²/r geometry made every tight
 * low-speed turn an event regardless of how gently it was driven.
 * v7: thresholds are per-direction (an engine can't pull what brakes can), and the speed gate now
 * takes the lower of GPS and an IMU-derived speed so a wandering fix can't fake its way past it.
 * v8: analysis streams the file rather than materialising it, so every sensor is read at the full
 * recorded rate. Detection itself is unchanged; the version moves only because the orientation and
 * gyro thinning v7 shipped with is gone, which can shift a borderline event by a hair.
 */
class RideEventStage(
    private val db: RidesafeDatabase,
    private val config: RideEventConfig = RideEventConfig(),
) : RideStage {
    override val id = "events"
    override val version = 8
    override val dependsOn = listOf("axis")
    override val restorable = true

    private var detector: StreamingDetector? = null

    /** Null for a ride that recorded no acceleration: there is nothing to detect, so skip the pass. */
    override fun sink(ctx: RideAnalysisContext): SampleSink? {
        if (!ctx.hasAccel) return null
        val detector = StreamingDetector(ctx.forwardAxis, config, ctx.ride.startedElapsedNanos)
        this.detector = detector
        return SampleSink { sample ->
            when (sample) {
                is LocationSample -> detector.onFix(sample)
                is MotionSample -> detector.onMotion(sample)
            }
        }
    }

    override suspend fun finish(ctx: RideAnalysisContext) {
        val events = detector?.finish()?.map { it.copy(rideId = ctx.ride.id) } ?: emptyList()
        db.rideEventDao().replaceForRide(ctx.ride.id, events)
        ctx.events = events
        // Both peaks are logged because they're the two numbers detection is tuned on, and reading
        // them off real rides beats guessing at thresholds. It also keeps "no events"
        // distinguishable from "detector broken".
        Log.i(
            TAG_EVENTS,
            "ride ${ctx.ride.id}: ${events.size} events, peak ${events.maxOfOrNull { it.peakG } ?: 0.0} g / " +
                "${events.maxOfOrNull { it.peakJerkGPerS } ?: 0.0} g/s",
        )
    }

    /** Stored events are the output; a later stage (the safety score) reads them without a file pass. */
    override suspend fun load(ctx: RideAnalysisContext): Boolean {
        ctx.events = db.rideEventDao().eventsFor(ctx.ride.id)
        return true
    }
}

private const val TAG_ROUTE = "RideAnalysis"
private const val TAG_EVENTS = "RideEvents"
