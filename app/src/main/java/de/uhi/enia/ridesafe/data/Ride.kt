package de.uhi.enia.ridesafe.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A recorded ride (entity DR-RID), holding only the summary that ride recording produces;
 * the full time-series (GPS + motion samples) lives off the database in a per-ride file
 * ([sampleFile], relative to the app's rides dir) so the DB stays lean (NFR-03/NFR-08).
 *
 * [startedAtEpochMs]/[endedAtEpochMs] are the start/end time (epoch millis = date + sub-second,
 * displayed to the second); [endedAtEpochMs] is null while a ride is in progress, and recovery
 * finalizes any such "dangling" ride from its file after a crash/kill (NFR-06). Time is stored
 * twice on purpose: epoch millis for display, and the monotonic [startedElapsedNanos] base so a
 * sample's `t` (elapsed-realtime nanos) can be mapped back to wall-clock time during analysis.
 *
 * [startLat]/[startLon]/[endLat]/[endLon] are the first/last GPS fix; null when the ride recorded
 * no fixes. [distanceMeters] and [avgSpeedMps] are deferred placeholders, left null at record time
 * and filled by the analysis pass that computes distance from the sample file (ANL-02);
 * [maxSpeedMps] is a direct GPS reading, so recording fills it live.
 *
 * [startAddress]/[endAddress] are the reverse-geocoded addresses for the first/last fix, built from
 * the Geocoder result's structured fields and stored newline-separated (street/place, then ZIP+city
 * — see formatAddress). Filled by the UI layer (not recording) so they can be displayed, indexed and
 * searched. Null until geocoded, or when geocoding is unavailable/offline — a later pass retries.
 *
 * [mergeGroupId] tags this ride as a "stop" inside a merged ride (MRG-01): all stops of one merged
 * ride share the same value (the smallest stop id at merge time); null means a standalone ride. There
 * is no separate merged-ride row and no stored stop order — stops order by [startedAtEpochMs] and the
 * aggregated metrics/addresses are derived on read (MRG-05, MRG-07).
 *
 * [startAddressId]/[endAddressId] are the saved addresses (DR-ADR) the start/end points fall into,
 * or null when none matches (ADR-07). Persisted by the re-match pass (rematchRides), which reruns on
 * any saved-address change and once per launch, so the display just resolves the id to the place.
 *
 * Which build of which analysis step last ran for this ride is tracked separately, in
 * [RideAnalysisState] — one row per step, so re-tuning one step doesn't invalidate the others.
 *
 * ponytail: notes/tags/purpose/safety score (DR-RID, ANL-01) are written by later UI/analysis
 * layers, not recording — add the columns via an ALTER-TABLE migration when those land.
 */
@Entity(tableName = "rides")
data class Ride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long? = null,
    val mergeGroupId: Long? = null,
    val startedAtEpochMs: Long,
    val startedElapsedNanos: Long,
    val endedAtEpochMs: Long? = null,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val distanceMeters: Double? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double = 0.0,
    val sampleFile: String,
    val startAddress: String? = null,
    val endAddress: String? = null,
    val startAddressId: Long? = null,
    val endAddressId: Long? = null,
)
