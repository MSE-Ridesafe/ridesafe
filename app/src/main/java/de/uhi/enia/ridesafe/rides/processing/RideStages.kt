package de.uhi.enia.ridesafe.rides.processing

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.rides.processing.event.ForwardAxisEstimator
import de.uhi.enia.ridesafe.rides.processing.event.RideEventConfig
import de.uhi.enia.ridesafe.rides.processing.event.StreamingDetector
import de.uhi.enia.ridesafe.rides.processing.score.ScoreWeights
import de.uhi.enia.ridesafe.rides.processing.score.ecoLevel
import de.uhi.enia.ridesafe.rides.processing.score.rideEcoProfile
import de.uhi.enia.ridesafe.rides.processing.score.scoreRide
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import de.uhi.enia.ridesafe.rides.recording.haversineMeters
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
    config: RideEventConfig = RideEventConfig(),
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
 * It also accumulates the ride's dynamics profile — how long was spent at each level of force and
 * onset rate — which is what the safety score is computed from. That rides along here rather than
 * forming its own stage because it is read off the very same conditioned signal the detector already
 * produces per sample; a separate stage would have to redo the rotation, projection and filtering to
 * see it.
 *
 * A ride with no acceleration samples yields an empty list and an empty profile. The two are not the
 * same thing downstream: the profile records how much of the ride was measurable at all, so a ride
 * recorded without a rotation vector is scored as *unscoreable* rather than as a flawless drive.
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
 * v9: the dynamics profile is accumulated and stored alongside the events (ANL-01). Detection is
 * untouched; the version moves because rides analysed by v8 hold no profile and therefore cannot be
 * scored.
 * v10: entry thresholds lowered ~10% across the board (jerk gates 1.0/0.8/1.0 → 0.9/0.7/0.9 g/s,
 * force bypasses 0.50/0.35 → 0.45/0.32 g), after real-logbook review found borderline-harsh
 * maneuvers going unrecorded. The peak floors stay put — what counts as *worth keeping* hasn't
 * changed, only how readily a maneuver opens an event.
 */
class RideEventStage(
    private val db: RidesafeDatabase,
    private val config: RideEventConfig = RideEventConfig(),
) : RideStage {
    override val id = "events"
    override val version = 10
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
        // Read after finish(), which drains the last of the held acceleration into the profile.
        val dynamics = detector?.dynamics()
        db.rideDao().setDynamics(ctx.ride.id, dynamics)
        ctx.dynamics = dynamics
        // Both peaks are logged because they're the two numbers detection is tuned on, and reading
        // them off real rides beats guessing at thresholds. It also keeps "no events"
        // distinguishable from "detector broken".
        Log.i(
            TAG_EVENTS,
            "ride ${ctx.ride.id}: ${events.size} events, peak ${events.maxOfOrNull { it.peakG } ?: 0.0} g / " +
                "${events.maxOfOrNull { it.peakJerkGPerS } ?: 0.0} g/s",
        )
    }

    /**
     * Stored events and the stored profile are the output; the score stage reads them without a file
     * pass. Restoring the profile matters as much as restoring the events: without it, re-deriving a
     * score for a ride whose detection is already current would find nothing to score.
     */
    override suspend fun load(ctx: RideAnalysisContext): Boolean {
        ctx.events = db.rideEventDao().eventsFor(ctx.ride.id)
        ctx.dynamics = ctx.ride.dynamics
        return true
    }
}

/**
 * Safety scoring (ANL-01): turn the ride's dynamics profile into 0–100 scores for braking,
 * acceleration and cornering, and one combined figure.
 *
 * Reads no samples. Everything it needs was left on the context by detection, so re-tuning the
 * scoring is a version bump and one query per ride — no sample file is opened, and a full re-score
 * of the whole logbook takes about as long as a scroll. That is the entire reason the profile stores
 * a distribution rather than a finished penalty: what counts as a bad drive stays a read-time
 * decision, exactly as what counts as a harsh event does.
 *
 * A ride with too little measurable driving is stored with no score rather than a poor one — see
 * [de.uhi.enia.ridesafe.data.SafetyScore].
 */
class ScoreStage(
    private val db: RidesafeDatabase,
    private val config: RideEventConfig = RideEventConfig(),
    private val weights: ScoreWeights = ScoreWeights(),
) : RideStage {
    override val id = "score"
    override val version = 2
    override val dependsOn = listOf("events")
    override val needsSamples = false

    override suspend fun finish(ctx: RideAnalysisContext) {
        val dynamics = ctx.dynamics
        val score = dynamics?.let { scoreRide(it, config, weights) }
        db.rideDao().setScore(ctx.ride.id, score)
        // The calibration record: collect these across the logbook to judge the ScoreWeights
        // constants against real driving, and bump [version] after changing any — which re-derives
        // every score from stored profiles without reading a sample file.
        Log.i(
            TAG_SCORE,
            "ride ${ctx.ride.id}: " +
                if (dynamics == null) {
                    "no profile"
                } else {
                    "%.0f s qualified (%.0f%% coverage), penalties %.2f/%.2f/%.2f, score %s".format(
                        dynamics.qualifiedSeconds,
                        dynamics.coverage * 100,
                        score?.brakingPenalty ?: 0.0,
                        score?.accelerationPenalty ?: 0.0,
                        score?.corneringPenalty ?: 0.0,
                        score?.let { "${it.total} (b ${it.braking} / a ${it.acceleration} / c ${it.cornering})" }
                            ?: "none — too little measurable driving",
                    )
                },
        )
    }
}

/**
 * Efficiency profiling (ANL-03): integrate the ride's kinematic energy accounting — friction
 * braking, idling, how speed was gained — from the filtered track and store it on the ride row.
 * The 0–3 eco level is derived from the profile at read time (see ecoLevel), so retuning what
 * counts as efficient never reopens a sample file.
 *
 * No sink — the pass driver already hands over the Kalman-filtered fixes, and the profile works off
 * their Doppler speed at the ~1 Hz the fixes arrive at. Sharing pass two with detection means this
 * costs no extra read of the sample file when both are due, and exactly one when it is the only
 * stage that moved.
 *
 * Depends on the route stage for its version rather than its output: a track that filters
 * differently is a different speed profile and therefore a different energy account.
 */
class EcoStage(
    private val db: RidesafeDatabase,
) : RideStage {
    override val id = "eco"
    override val version = 1
    override val dependsOn = listOf("route")

    override suspend fun finish(ctx: RideAnalysisContext) {
        val eco = rideEcoProfile(ctx.filteredFixes.orEmpty().map { it.fix })
        db.rideDao().setEco(ctx.ride.id, eco)
        // A ride whose track filtered to nothing legitimately has no profile; it is stamped anyway,
        // like the route stage does, so it isn't re-read every launch. The logged figures are the
        // calibration record for EcoKnobs, the same way ScoreStage's line feeds ScoreWeights.
        Log.i(
            TAG_ECO,
            "ride ${ctx.ride.id}: " +
                (
                    eco?.let {
                        "brake %.0f J/kg/km, idle %.0f%%, hard-accel %.0f%% -> level %s".format(
                            it.brakeJPerKgPerKm,
                            it.idleShare * 100,
                            it.hardAccelShare * 100,
                            ecoLevel(it)?.toString() ?: "none",
                        )
                    } ?: "no profile"
                ),
        )
    }
}

private const val TAG_ROUTE = "RideAnalysis"
private const val TAG_EVENTS = "RideEvents"
private const val TAG_ECO = "RideEco"
private const val TAG_SCORE = "RideScore"

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
    override val version = 1

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
