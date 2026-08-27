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
import de.uhi.enia.ridesafe.rides.recording.haversineMeters
import de.uhi.enia.ridesafe.rides.recording.trackDistanceMeters

const val AXIS_VERSION = 1
const val EVENTS_VERSION = 8
const val ENDPOINTS_VERSION = 1

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
    config: RideEventConfig = RideEventConfig(),
) : RideStage {
    override val id = "axis"
    override val version = AXIS_VERSION
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
    override val version = EVENTS_VERSION
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

/**
 * How far a ride's recorded endpoint must sit from the filtered one before it is treated as wrong
 * rather than merely smoothed. The filter nudges every fix by a few meters, so some tolerance is
 * required; a street address rarely changes below this, while the failure mode being corrected —
 * a fused-provider fix from Wi-Fi or cell towers — misses by hundreds of meters to kilometers.
 *
 * Raise it and endpoints one street over keep their wrong address; lower it and rides are
 * re-geocoded for a move too small to change the answer, each call a chance to fail offline.
 */
private const val ENDPOINT_MOVED_METERS = 50.0

/**
 * Correct a ride's start/end position from the filtered track (ANL-02).
 *
 * Recording stores the raw first and last fix, and those two are the likeliest in the whole ride to
 * be wrong: GPS is still converging at the start and the phone is often indoors again by the end,
 * so the fused provider falls back to Wi-Fi and cell towers. The Kalman pass already rejects such a
 * fix — it simply never reached the columns the rest of the app reads, so the address, the matched
 * saved place and the map pins were all derived from a point the ride never visited.
 *
 * Only writes when an endpoint actually moved [ENDPOINT_MOVED_METERS]; the filter shifts every fix
 * slightly, and re-geocoding a ride whose start moved three meters costs a network call to be told
 * the same street.
 *
 * A separate stage rather than part of [RouteStage] on purpose: it means a change to *this* logic
 * re-derives endpoints alone, leaving the detector's stamps — and their two file passes — untouched.
 */
class RideEndpointStage(
    private val db: RidesafeDatabase,
) : RideStage {
    override val id = "endpoints"
    override val version = ENDPOINTS_VERSION

    // Not for its output — the fixes come from the pass driver — but for its version: a filter that
    // behaves differently picks different endpoints.
    override val dependsOn = listOf("route")

    override suspend fun finish(ctx: RideAnalysisContext) {
        val fixes = ctx.filteredFixes.orEmpty()
        if (fixes.isEmpty()) return // no usable track; the recorded endpoints are all there is
        val ride = ctx.ride
        val dao = db.rideDao()

        val first = fixes.first().fix
        if (movedFar(ride.startLat, ride.startLon, first.lat, first.lon)) {
            Log.i(TAG_ROUTE, "ride ${ride.id}: start was off, correcting and re-geocoding")
            dao.correctStart(ride.id, first.lat, first.lon)
        }
        val last = fixes.last().fix
        if (movedFar(ride.endLat, ride.endLon, last.lat, last.lon)) {
            Log.i(TAG_ROUTE, "ride ${ride.id}: end was off, correcting and re-geocoding")
            dao.correctEnd(ride.id, last.lat, last.lon)
        }
    }
}

/**
 * Whether a filtered endpoint sits far enough from the recorded one to be a correction rather than
 * smoothing. False when nothing was recorded to compare against — there is no evidence of a mistake.
 */
internal fun movedFar(
    recordedLat: Double?,
    recordedLon: Double?,
    filteredLat: Double,
    filteredLon: Double,
): Boolean {
    if (recordedLat == null || recordedLon == null) return false
    return haversineMeters(recordedLat, recordedLon, filteredLat, filteredLon) > ENDPOINT_MOVED_METERS
}
