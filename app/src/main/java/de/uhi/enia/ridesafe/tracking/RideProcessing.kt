package de.uhi.enia.ridesafe.tracking

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import de.uhi.enia.ridesafe.data.Ride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.math3.filter.DefaultMeasurementModel
import org.apache.commons.math3.filter.DefaultProcessModel
import org.apache.commons.math3.filter.KalmanFilter
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.RealMatrix
import org.apache.commons.math3.linear.RealVector
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos

private const val TAG = "RideProcessing"

/**
 * Off-DB processing of a recorded ride's GPS track (ANL-02): Kalman-smooth the raw fixes (rejecting
 * impossible jumps), then store an RDP-simplified route in a small per-ride sidecar file and return
 * the distance/avg-speed metrics for the caller to persist on the [Ride] row. The raw NDJSON sample
 * file stays the source of truth — this output is derived and additive, so it can be regenerated.
 *
 * Distance + average speed computed from the filtered track, for the
 * [Ride.distanceMeters]/[Ride.avgSpeedMps] columns. */
data class RideMetrics(
    val distanceMeters: Double,
    val avgSpeedMps: Double,
)

/**
 * Version of the GPS processing pass, carried in the sidecar's filename so a bump re-processes every
 * ride — the same trick [de.uhi.enia.ridesafe.tracking.ANALYZER_VERSION] plays for driving events,
 * but on a file rather than a column, since the sidecar is already the "has been processed" marker.
 *
 * v2: the filter drops outliers instead of predicting through them, restarts after a GPS gap, and
 * discards uncorroborated runs — v1 tracks ended at the first bogus fix following an outage and
 * free-ran off the map from there, taking the distance with them.
 */
const val ROUTE_VERSION = 2

/** The processed-route sidecar for a ride, next to its raw sample file (e.g. `ride_123.route.v2`). */
fun processedRouteFile(
    appContext: Context,
    ride: Ride,
): File = File(ridesDir(appContext), ride.sampleFile.removeSuffix(".ndjson.gz") + ".route.v" + ROUTE_VERSION)

/** Delete sidecars written by an older [ROUTE_VERSION]; their rides are about to be re-processed. */
fun pruneStaleRoutes(appContext: Context) {
    ridesDir(appContext)
        .listFiles { f -> f.name.contains(".route") && !f.name.endsWith(".route.v$ROUTE_VERSION") }
        ?.forEach { it.delete() }
}

/**
 * Process one ride end-to-end: read its raw fixes, Kalman-filter them, write the simplified route to
 * the sidecar, and return distance (great-circle over the filtered track) and average speed (distance
 * over the ride's wall-clock duration, matching the detail view). Null when the ride has no usable
 * fixes — the file is missing/empty/corrupt — so the caller leaves the metrics null and retries later.
 */
suspend fun processRide(
    appContext: Context,
    ride: Ride,
): RideMetrics? =
    withContext(Dispatchers.IO) {
        val rawFile = File(ridesDir(appContext), ride.sampleFile)
        val raw = if (rawFile.exists()) readRideLocations(rawFile) else emptyList()
        if (raw.isEmpty()) return@withContext null

        val filtered = kalmanFilterLocations(raw)
        // Worst reported accuracy is the tell for *why* a ride's track was bad. The fused provider
        // keeps emitting when GNSS is gone, from Wi-Fi and cell towers, and those fixes land hundreds
        // of meters to kilometers away — but nearly always say so. A large drop count next to a
        // modest worst accuracy is the other case: fixes that were wrong without admitting it.
        Log.i(TAG, "ride ${ride.id}: kept ${filtered.size}/${raw.size} fixes, worst accuracy ${raw.maxOf { it.accuracy }} m")
        // Everything filtered out means no usable track, not a zero-length ride: leave the metrics
        // null (the caller retries later) and write no sidecar, so the map falls back to raw fixes.
        if (filtered.isEmpty()) return@withContext null

        val distance = trackDistanceMeters(filtered)
        val durationSec = ride.endedAtEpochMs?.let { (it - ride.startedAtEpochMs) / 1000.0 } ?: 0.0
        val avgSpeed = if (durationSec > 0) distance / durationSec else 0.0

        val simplified = simplifyRoute(filtered.map { LatLng(it.lat, it.lon) })
        writeProcessedRoute(processedRouteFile(appContext, ride), simplified)
        RideMetrics(distance, avgSpeed)
    }

/** RDP-simplify a route (android-maps-utils, tolerance in meters). A <3-point route can't simplify. */
fun simplifyRoute(
    points: List<LatLng>,
    toleranceMeters: Double = 5.0, // calibration knob: larger = fewer points / coarser route
): List<LatLng> = if (points.size < 3) points else PolyUtil.simplify(points, toleranceMeters)

/** Store a route as a Google encoded-polyline string (compact); empty list -> empty file. */
fun writeProcessedRoute(
    file: File,
    points: List<LatLng>,
) = file.writeText(PolyUtil.encode(points))

/** Read back a sidecar route; null when it doesn't exist yet or can't be decoded. */
fun readProcessedRoute(file: File): List<LatLng>? =
    if (file.exists()) runCatching { PolyUtil.decode(file.readText()) }.getOrNull() else null

/** Great-circle length of a [LatLng] path — the fallback distance for a ride not yet processed. */
fun latLngDistanceMeters(points: List<LatLng>): Double {
    var total = 0.0
    for (i in 1 until points.size) {
        total +=
            haversineMeters(
                points[i - 1].latitude,
                points[i - 1].longitude,
                points[i].latitude,
                points[i].longitude,
            )
    }
    return total
}

/**
 * Robustly smooth a GPS track: drop the fixes that aren't real, Kalman-smooth the ones that are.
 *
 * The filter itself is a constant-velocity model in a local-meter frame (equirectangular around the
 * first fix). State is `[x, y, vx, vy]`; only position is measured; each fix's own
 * [LocationSample.accuracy] sets the measurement noise, so sloppy fixes are pulled in less. dt comes
 * from each sample's real timestamp (never assumed uniform), so the model is rebuilt per step.
 *
 * On top of it sit the rejection rules, each aimed at a way a phone produces a position that is
 * hundreds of meters to tens of kilometers wrong:
 *
 * [maxAccuracyMeters] is a hard drop. Whenever GNSS is unavailable — a tunnel, a garage, a ride that
 * ends indoors — the fused provider keeps emitting, now derived from WiFi and cell towers, and a
 * stale access-point entry or a large rural cell puts the fix kilometers from the vehicle. Those
 * fixes almost always admit it in their accuracy, which this filter used to read only as a soft
 * weight. An accuracy of 0 means the fix carried none at all, so it is treated as the worst
 * tolerable rather than, as before, the best possible.
 *
 * [maxSpeedMps] drops a fix implying a faster-than-that move from the current estimate. Dropped, not
 * replaced by the prediction: a predicted point is a position nobody measured, and drawing those is
 * what used to put an invented tail on the map.
 *
 * [maxGapSeconds], [maxConsecutiveRejects] and [minSegmentFixes] are the hindsight rules, and they
 * are what fixes the failure this filter had. The speed gate alone allows a jump of
 * `maxSpeedMps * dt`, so it is at its most permissive exactly when fixes deserve it least: right
 * after a GPS outage. A bogus fix 5 km away following a two-minute gap implies only 42 m/s, sails
 * through, and takes both the position and — worse — the velocity estimate with it. Every true fix
 * after that looks like a 5 km jump from an estimate now flying off at 80 m/s, so the gate rejects
 * all of them, permanently. The track ended at the bad point and ran off the map.
 *
 * So: a gap longer than [maxGapSeconds] leaves the velocity estimate meaningless, and the filter
 * restarts at the next fix instead of predicting through it. [maxConsecutiveRejects] rejections in a
 * row mean the estimate, not the world, is the thing that is wrong — restart there too. And a run of
 * fewer than [minSegmentFixes] between restarts is a position nothing else corroborates, so it is
 * discarded whole. A lone bogus fix that lies about its accuracy is precisely that: a one-fix island,
 * costing the handful of good fixes spent disproving it rather than the entire rest of the ride.
 *
 * Returns the surviving fixes in order, with smoothed lat/lon — fewer than came in, never more, and
 * possibly none at all when no fix was usable. Untouched when there are fewer than two fixes. The
 * constants are calibration knobs; defaults suit road use.
 */
fun kalmanFilterLocations(
    locations: List<LocationSample>,
    accelNoiseStdDev: Double = 2.0, // m/s^2 process noise — how much the speed is allowed to wander
    maxSpeedMps: Double = 75.0, // ~270 km/h jump gate — fixes implying more are dropped as outliers
    minAccuracyMeters: Double = 5.0, // floor on reported accuracy, so an over-confident fix isn't trusted blindly
    maxAccuracyMeters: Double = 50.0, // ceiling — past this it's a WiFi/cell guess, not a position
    maxGapSeconds: Double = 20.0, // past this the velocity estimate is stale; restart rather than predict
    maxConsecutiveRejects: Int = 3, // this many rejections running means the estimate is the wrong one
    minSegmentFixes: Int = 3, // a run shorter than this is corroborated by nothing; drop it
): List<LocationSample> {
    if (locations.size < 2) return locations

    val first = locations.first()
    val lat0 = first.lat
    val lon0 = first.lon
    val cosLat0 = cos(Math.toRadians(lat0))

    fun projX(lon: Double) = Math.toRadians(lon - lon0) * cosLat0 * EARTH_RADIUS_M

    fun projY(lat: Double) = Math.toRadians(lat - lat0) * EARTH_RADIUS_M

    fun unprojLat(y: Double) = lat0 + Math.toDegrees(y / EARTH_RADIUS_M)

    fun unprojLon(x: Double) = lon0 + Math.toDegrees(x / (EARTH_RADIUS_M * cosLat0))

    fun measVar(accuracy: Float): Double =
        (if (accuracy <= 0f) maxAccuracyMeters else accuracy.toDouble().coerceAtLeast(minAccuracyMeters))
            .let { it * it }

    val accelVar = accelNoiseStdDev * accelNoiseStdDev
    val out = ArrayList<LocationSample>(locations.size)
    val segment = ArrayList<LocationSample>() // fixes since the last restart, held until corroborated
    var state: RealVector = ArrayRealVector(4) // both are overwritten by the first seed below
    var cov: RealMatrix = Array2DRowRealMatrix(4, 4)
    var seeded = false
    var lastT = 0L
    var rejects = 0

    fun flushSegment() {
        if (segment.size >= minSegmentFixes) out.addAll(segment)
        segment.clear()
    }

    for (loc in locations) {
        if (loc.accuracy <= 0f || loc.accuracy > maxAccuracyMeters) continue
        if (seeded && loc.t <= lastT) continue // stale or duplicate delivery; it adds nothing

        val dt = if (seeded) (loc.t - lastT) / 1e9 else Double.MAX_VALUE
        var smoothedBearing: Float? = null

        if (dt <= maxGapSeconds && rejects < maxConsecutiveRejects) {
            val prevLat = unprojLat(state.getEntry(1))
            val prevLon = unprojLon(state.getEntry(0))
            if (haversineMeters(prevLat, prevLon, loc.lat, loc.lon) / dt > maxSpeedMps) {
                rejects++
                continue
            }
            val (nextState, nextCov) =
                kalmanStep(state, cov, dt, projX(loc.lon), projY(loc.lat), measVar(loc.accuracy), accelVar)
            state = nextState
            cov = nextCov
            // Bearing is overwritten with the filter's heading, which beats the raw GPS field — that
            // one is noise below walking pace. vx/vy are east/north in the local meter frame, hence
            // atan2(east, north) for a compass bearing. A just-seeded fix keeps its raw bearing
            // instead: the filter's velocity is still zero there and would read as due north.
            smoothedBearing =
                ((Math.toDegrees(atan2(state.getEntry(2), state.getEntry(3))) + 360.0) % 360.0).toFloat()
        } else {
            flushSegment()
            val posVar = measVar(loc.accuracy)
            state = ArrayRealVector(doubleArrayOf(projX(loc.lon), projY(loc.lat), 0.0, 0.0))
            // Large initial velocity covariance (it is genuinely unknown) so the next good fixes pull it in.
            cov =
                Array2DRowRealMatrix(
                    arrayOf(
                        doubleArrayOf(posVar, 0.0, 0.0, 0.0),
                        doubleArrayOf(0.0, posVar, 0.0, 0.0),
                        doubleArrayOf(0.0, 0.0, 100.0, 0.0),
                        doubleArrayOf(0.0, 0.0, 0.0, 100.0),
                    ),
                )
            seeded = true
        }

        rejects = 0
        lastT = loc.t
        // Speed is deliberately left alone. The filter's velocity comes from differencing positions,
        // so a position that jumps produces a speed that jumps with it — a parked car whose fix
        // wanders reads as tens of km/h and sails through the event detector's speed gate. The raw
        // field is GNSS Doppler, computed from carrier frequency shift rather than position, and
        // stays trustworthy even while the position is badly wrong.
        segment.add(
            loc.copy(
                lat = unprojLat(state.getEntry(1)),
                lon = unprojLon(state.getEntry(0)),
                bearing = smoothedBearing ?: loc.bearing,
            ),
        )
    }
    flushSegment()
    return out
}

/**
 * One predict+correct step. commons-math3's [KalmanFilter] fixes its transition/noise matrices at
 * construction, so to honor the per-step dt we build a fresh filter seeded with the previous estimate
 * and covariance.
 */
private fun kalmanStep(
    prevState: RealVector,
    prevCov: RealMatrix,
    dt: Double,
    measX: Double,
    measY: Double,
    measVar: Double,
    accelVar: Double,
): Pair<RealVector, RealMatrix> {
    val a =
        Array2DRowRealMatrix(
            arrayOf(
                doubleArrayOf(1.0, 0.0, dt, 0.0),
                doubleArrayOf(0.0, 1.0, 0.0, dt),
                doubleArrayOf(0.0, 0.0, 1.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0),
            ),
        )
    val dt2 = dt * dt
    val dt3 = dt2 * dt
    val dt4 = dt3 * dt
    // Discrete white-noise-acceleration process noise for a constant-velocity model.
    val q =
        Array2DRowRealMatrix(
            arrayOf(
                doubleArrayOf(dt4 / 4, 0.0, dt3 / 2, 0.0),
                doubleArrayOf(0.0, dt4 / 4, 0.0, dt3 / 2),
                doubleArrayOf(dt3 / 2, 0.0, dt2, 0.0),
                doubleArrayOf(0.0, dt3 / 2, 0.0, dt2),
            ),
        ).scalarMultiply(accelVar)
    val h =
        Array2DRowRealMatrix(
            arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 1.0, 0.0, 0.0),
            ),
        )
    val r =
        Array2DRowRealMatrix(
            arrayOf(
                doubleArrayOf(measVar, 0.0),
                doubleArrayOf(0.0, measVar),
            ),
        )
    val kf =
        KalmanFilter(
            DefaultProcessModel(a, null, q, prevState, prevCov),
            DefaultMeasurementModel(h, r),
        )
    kf.predict()
    kf.correct(ArrayRealVector(doubleArrayOf(measX, measY)))
    return kf.stateEstimationVector to kf.errorCovarianceMatrix
}
