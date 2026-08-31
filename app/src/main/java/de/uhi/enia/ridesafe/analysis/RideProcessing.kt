package de.uhi.enia.ridesafe.analysis

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import de.uhi.enia.ridesafe.core.location.EARTH_RADIUS_M
import de.uhi.enia.ridesafe.core.location.haversineMeters
import de.uhi.enia.ridesafe.data.entity.Ride
import de.uhi.enia.ridesafe.data.file.LocationSample
import de.uhi.enia.ridesafe.data.file.ridesDir
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
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Off-DB processing of a recorded ride's GPS track (ANL-02): Kalman-smooth the raw fixes (rejecting
 * impossible jumps), then store an RDP-simplified route in a small per-ride sidecar file and return
 * the distance/avg-speed metrics for the caller to persist on the [de.uhi.enia.ridesafe.data.entity.Ride] row. The raw NDJSON sample
 * file stays the source of truth — this output is derived and additive, so it can be regenerated.
 *
 * Distance + average speed computed from the filtered track, for the
 * [de.uhi.enia.ridesafe.data.entity.Ride.distanceMeters]/[de.uhi.enia.ridesafe.data.entity.Ride.avgSpeedMps] columns. */
data class RideMetrics(
    val distanceMeters: Double,
    val avgSpeedMps: Double,
)

/**
 * Version of the GPS processing pass. Whether a ride is due for it is tracked in `ride_analysis` like
 * every other step's, but the number is also carried in the sidecar's filename, so a bump can't leave
 * an older pass's route lying next to a newer one's. [RouteStage] stamps it and documents what
 * changed between versions.
 */
const val ROUTE_VERSION = 2

/** The processed-route sidecar for a ride, next to its raw sample file (e.g. `ride_123.route.v2`). */
fun processedRouteFile(
    appContext: Context,
    ride: Ride,
): File =
    File(
        ridesDir(appContext),
        ride.sampleFile.removeSuffix(".ndjson.gz") + ".route.v" + ROUTE_VERSION,
    )

/** Delete sidecars written by an older [ROUTE_VERSION]; their rides are about to be re-processed. */
fun pruneStaleRoutes(appContext: Context) {
    ridesDir(appContext)
        .listFiles { f -> f.name.contains(".route") && !f.name.endsWith(".route.v$ROUTE_VERSION") }
        ?.forEach { it.delete() }
}

/** RDP-simplify a route (android-maps-utils, tolerance in meters). A <3-point route can't simplify. */
fun simplifyRoute(
    points: List<LatLng>,
    toleranceMeters: Double = 5.0, // calibration knob: larger = fewer points / coarser route
): List<LatLng> = if (points.size < 3) points else PolyUtil.simplify(points, toleranceMeters)

/** Store a route as a Google encoded-polyline string (compact); empty list -> empty file. */
suspend fun writeProcessedRoute(
    file: File,
    points: List<LatLng>,
): Unit =
    withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        // Deliberately does not contain ".route": stale-route pruning must never mistake an in-flight
        // publication for an old sidecar.
        val temporary = File(file.parentFile, ".ridesafe_sidecar_${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(PolyUtil.encode(points).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

/** Read back a sidecar route; null when it doesn't exist yet or can't be decoded. */
suspend fun readProcessedRoute(file: File): List<LatLng>? =
    withContext(Dispatchers.IO) {
        if (file.exists()) runCatching { PolyUtil.decode(file.readText()) }.getOrNull() else null
    }

/** Great-circle length of a [LatLng] path — the fallback distance for a ride not yet processed. */
fun latLngDistanceMeters(points: List<LatLng>): Double =
    points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }

/**
 * The GPS track filter as an incremental stage: feed it fixes in order, it hands back filtered ones.
 * State is O(1) — the only buffering is up to [minSegmentFixes] fixes held back while it waits to see
 * whether their run is long enough to be believed, which is what lets a whole ride stream through
 * without being materialised. [kalmanFilterLocations] is simply this driven over a list.
 *
 * Constant-velocity model in a local-meter frame (equirectangular about the first accepted fix).
 * State is `[x, y, vx, vy]`; only position is measured. Robustness comes from four levers: each
 * fix's own accuracy sets the measurement noise, a fix implying faster than [maxSpeedMps] is
 * rejected as an impossible jump, [maxConsecutiveRejects] rejections in a row mean the *estimate* is
 * the wrong one and the filter restarts, and a gap over [maxGapSeconds] restarts rather than
 * predicts through stale velocity. A run shorter than [minSegmentFixes] is corroborated by nothing
 * and is dropped. dt comes from each sample's real timestamp, never assumed uniform.
 *
 * Single-use and single-threaded: one instance per analysis, driven by one coroutine. Concurrent
 * rides each get their own, which is what keeps parallel analysis safe.
 *
 * @property accelNoiseStdDev Process noise, in m/s² — how freely the model lets speed wander between
 * fixes. Raise it and the filter trusts each measurement more, tracking real manoeuvres closely but
 * passing GPS noise through; lower it and the track smooths harder, lagging genuine changes.
 *
 * @property maxSpeedMps Jump gate (default ~270 km/h). A fix implying a faster move than this from
 * the current estimate is rejected as impossible rather than believed. Raise it and large teleports
 * start being accepted, dragging the track off the road; lower it and genuine motorway speed can be
 * mistaken for a jump.
 *
 * @property minAccuracyMeters Floor on a fix's reported accuracy before it sets the measurement
 * noise, so an over-confident fix claiming near-zero error cannot dominate. Raise it and every fix
 * is treated as vaguer, smoothing more; lower it and optimistic accuracy claims are taken at face
 * value.
 *
 * @property maxAccuracyMeters Ceiling on reported accuracy; worse fixes are discarded outright. The
 * fused provider keeps emitting when GNSS is gone, from Wi-Fi and cell towers, and those land
 * hundreds of meters to kilometers away — but nearly always admit it in their accuracy. Raise it and
 * those guesses re-enter the track; lower it and legitimately weak GNSS fixes are thrown away.
 *
 * @property maxGapSeconds Longest gap between fixes the filter will predict across; past it the
 * velocity estimate is stale, so it restarts rather than free-running. Raise it and the filter
 * coasts on an old velocity through long outages, drifting off the map; lower it and ordinary GPS
 * hiccups force a restart, losing the smoothing already built up.
 *
 * @property maxConsecutiveRejects How many rejections in a row mean the *estimate* is wrong rather
 * than the fixes. Without it, one bad estimate rejects every subsequent good fix forever and the
 * track free-runs. Raise it and recovery takes longer; lower it and a short burst of genuinely bad
 * fixes forces a needless restart.
 *
 * @property minSegmentFixes Shortest run of fixes worth believing; a run ending before this length
 * is discarded, since a couple of fixes after a restart are corroborated by nothing and would leave
 * isolated specks on the map. Raise it and more short but real fragments are dropped; lower it and
 * noise survives as stray segments. Also bounds the filter's buffering — it holds at most this many
 * fixes, which is what keeps it O(1) and streamable.
 */
class TrackFilter(
    private val accelNoiseStdDev: Double = 2.0,
    private val maxSpeedMps: Double = 75.0,
    private val minAccuracyMeters: Double = 5.0,
    private val maxAccuracyMeters: Double = 50.0,
    private val maxGapSeconds: Double = 20.0,
    private val maxConsecutiveRejects: Int = 3,
    private val minSegmentFixes: Int = 3,
) {
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var cosLat0 = 1.0
    private var originSet = false
    private var state: RealVector = ArrayRealVector(4)
    private var cov: RealMatrix = Array2DRowRealMatrix(4, 4)
    private var seeded = false
    private var lastT = 0L
    private var rejects = 0
    private val accelVar = accelNoiseStdDev * accelNoiseStdDev

    // Held back until the run they belong to is long enough to believe; never exceeds minSegmentFixes.
    private val pending = ArrayList<LocationSample>(minSegmentFixes)
    private var segmentFixes = 0

    private fun projX(lon: Double) = Math.toRadians(lon - lon0) * cosLat0 * EARTH_RADIUS_M

    private fun projY(lat: Double) = Math.toRadians(lat - lat0) * EARTH_RADIUS_M

    private fun unprojLat(y: Double) = lat0 + Math.toDegrees(y / EARTH_RADIUS_M)

    private fun unprojLon(x: Double) = lon0 + Math.toDegrees(x / (EARTH_RADIUS_M * cosLat0))

    private fun measVar(accuracy: Float): Double =
        (if (accuracy <= 0f) maxAccuracyMeters else accuracy.toDouble().coerceAtLeast(minAccuracyMeters))
            .let { it * it }

    /** Feed one raw fix in time order; [out] receives whatever became confirmed as a result. */
    fun update(
        loc: LocationSample,
        out: (LocationSample) -> Unit,
    ) {
        if (loc.accuracy <= 0f || loc.accuracy > maxAccuracyMeters) return
        if (seeded && loc.t <= lastT) return // stale or duplicate delivery; it adds nothing
        if (!originSet) {
            lat0 = loc.lat
            lon0 = loc.lon
            cosLat0 = cos(Math.toRadians(lat0))
            originSet = true
        }

        val dt = if (seeded) (loc.t - lastT) / 1e9 else Double.MAX_VALUE
        var smoothedBearing: Float? = null

        if (dt <= maxGapSeconds && rejects < maxConsecutiveRejects) {
            val prevLat = unprojLat(state.getEntry(1))
            val prevLon = unprojLon(state.getEntry(0))
            if (haversineMeters(prevLat, prevLon, loc.lat, loc.lon) / dt > maxSpeedMps) {
                rejects++
                return
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
            dropUncorroborated()
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
        emit(
            loc.copy(
                lat = unprojLat(state.getEntry(1)),
                lon = unprojLon(state.getEntry(0)),
                bearing = smoothedBearing ?: loc.bearing,
            ),
            out,
        )
    }

    /**
     * Release a filtered fix, holding it back until its run is long enough to be corroborated. Once
     * the run clears [minSegmentFixes] the backlog goes out and everything after it flows straight
     * through, so the buffer never grows past that bound.
     */
    private fun emit(
        filtered: LocationSample,
        out: (LocationSample) -> Unit,
    ) {
        segmentFixes++
        if (segmentFixes < minSegmentFixes) {
            pending.add(filtered)
            return
        }
        if (pending.isNotEmpty()) {
            pending.forEach(out)
            pending.clear()
        }
        out(filtered)
    }

    /** A run that never reached [minSegmentFixes] is backed by nothing; discard it. */
    private fun dropUncorroborated() {
        pending.clear()
        segmentFixes = 0
    }

    /** Call once the stream ends, so a trailing run too short to believe is discarded. */
    fun finish() = dropUncorroborated()
}

/**
 * Kalman-smooth a whole GPS track — [TrackFilter] driven over a list. Returns one filtered fix per
 * accepted input; fixes the filter rejected or could not corroborate are absent, so the result can
 * be shorter than the input. Untouched when there are fewer than two fixes.
 */
fun kalmanFilterLocations(locations: List<LocationSample>): List<LocationSample> {
    if (locations.size < 2) return locations
    val filter = TrackFilter()
    val out = ArrayList<LocationSample>(locations.size)
    for (loc in locations) filter.update(loc, out::add)
    filter.finish()
    return out
}

/**
 * One predict+correct step. commons-math3's [org.apache.commons.math3.filter.KalmanFilter] fixes its transition/noise matrices at
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
