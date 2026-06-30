package de.uhi.enia.ridesafe.tracking

import android.content.Context
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
import kotlin.math.cos

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

/** The processed-route sidecar for a ride, next to its raw sample file (e.g. `ride_123.route`). */
fun processedRouteFile(
    appContext: Context,
    ride: Ride,
): File = File(ridesDir(appContext), ride.sampleFile.removeSuffix(".ndjson.gz") + ".route")

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
 * Kalman-smooth a GPS track with a constant-velocity model in a local-meter frame (equirectangular
 * around the first fix). State is `[x, y, vx, vy]`; only position is measured. Two robustness levers:
 * each fix's own [LocationSample.accuracy] sets the measurement noise (sloppy fixes pulled in less),
 * and a fix implying a faster-than-[maxSpeedMps] move from the last estimate is treated as an
 * impossible jump — the filter predicts through it instead of correcting on it. dt is taken from each
 * sample's real timestamp (never assumed uniform), so the model is rebuilt per step.
 *
 * Returns one filtered [LocationSample] per input (outliers replaced by the prediction). Untouched
 * when there are fewer than two fixes. The constants are calibration knobs; defaults suit road use.
 */
fun kalmanFilterLocations(
    locations: List<LocationSample>,
    accelNoiseStdDev: Double = 2.0, // m/s^2 process noise — how much the speed is allowed to wander
    maxSpeedMps: Double = 75.0, // ~270 km/h jump gate — fixes implying more are rejected as outliers
    minAccuracyMeters: Double = 5.0, // floor on reported GPS accuracy, so a 0/over-confident fix isn't trusted blindly
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

    fun measVar(accuracy: Float): Double = maxOf(accuracy.toDouble(), minAccuracyMeters).let { it * it }

    val accelVar = accelNoiseStdDev * accelNoiseStdDev
    val pos0Var = measVar(first.accuracy)
    var state: RealVector = ArrayRealVector(doubleArrayOf(projX(lon0), projY(lat0), 0.0, 0.0))
    // Large initial covariance (unknown velocity especially) so the first good fixes pull the estimate in.
    var cov: RealMatrix =
        Array2DRowRealMatrix(
            arrayOf(
                doubleArrayOf(pos0Var, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, pos0Var, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 100.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 100.0),
            ),
        )

    val out = ArrayList<LocationSample>(locations.size)
    out.add(first) // first fix is the filter's seed; emitted as-is
    for (i in 1 until locations.size) {
        val loc = locations[i]
        val dt =
            ((loc.t - locations[i - 1].t) / 1e9).coerceAtLeast(1e-3) // seconds; guard zero/duplicate stamps
        val prevLat = unprojLat(state.getEntry(1))
        val prevLon = unprojLon(state.getEntry(0))
        val impliedSpeed = haversineMeters(prevLat, prevLon, loc.lat, loc.lon) / dt
        val accept = impliedSpeed <= maxSpeedMps

        val (newState, newCov) =
            kalmanStep(
                state,
                cov,
                dt,
                projX(loc.lon),
                projY(loc.lat),
                measVar(loc.accuracy),
                accelVar,
                accept,
            )
        state = newState
        cov = newCov
        out.add(loc.copy(lat = unprojLat(state.getEntry(1)), lon = unprojLon(state.getEntry(0))))
    }
    return out
}

/**
 * One predict+correct step. commons-math3's [KalmanFilter] fixes its transition/noise matrices at
 * construction, so to honor the per-step dt we build a fresh filter seeded with the previous estimate
 * and covariance. [correct] false skips the measurement update (a rejected outlier) — predict only.
 */
private fun kalmanStep(
    prevState: RealVector,
    prevCov: RealMatrix,
    dt: Double,
    measX: Double,
    measY: Double,
    measVar: Double,
    accelVar: Double,
    correct: Boolean,
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
    if (correct) kf.correct(ArrayRealVector(doubleArrayOf(measX, measY)))
    return kf.stateEstimationVector to kf.errorCovarianceMatrix
}
