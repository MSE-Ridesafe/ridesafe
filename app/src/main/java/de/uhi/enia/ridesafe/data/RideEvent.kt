package de.uhi.enia.ridesafe.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** The kinds of harsh driving detected from the vehicle-frame acceleration (ANL-01). */
enum class RideEventType { BRAKING, ACCELERATION, CORNERING }

/** The Material Symbol representing an event type on the map and in lists. */
fun RideEventType.symbol(): String =
    when (this) {
        RideEventType.BRAKING -> "podiatry"
        RideEventType.ACCELERATION -> "rocket_launch"
        RideEventType.CORNERING -> "turn_sharp_right"
    }

/**
 * One detected harsh-driving event (ANL-01), derived from a ride's motion samples. Derived and
 * regenerable: the raw NDJSON sample file stays the source of truth, so re-running detection is
 * "delete this ride's rows, analyze again" — see [RideAnalysisState].
 *
 * Detection stores both magnitudes rather than a yes/no verdict, so *how harsh counts as harsh*
 * stays a read-time decision. Re-tuning severity is a query change, never a re-analysis.
 *
 * [peakJerkGPerS] is the fastest rate the force built, and is what actually separates harsh from
 * merely forceful: a tight residential corner reaches 0.4 g perfectly smoothly, while a stab at the
 * brakes is harsh at half that. [peakG] still matters at the top end, where a maneuver is hard
 * however gently it was started. Scoring should weigh both.
 *
 * [startOffsetMs] is measured from the ride's start rather than on the sample stream's monotonic
 * clock, so an event is readable on its own ("hard brake 4:12 in") without joining to the ride row.
 * [durationMs] is stored rather than derived from an end offset because sustained harshness is a
 * scoring input in its own right — a 4-second corner is worse than a 0.3-second flick at the same
 * peak — and it leaves nothing redundant, since the end is just start + duration.
 *
 * [speedMps] is the interpolated GPS speed at the peak, and [lat]/[lon] the interpolated position
 * (null when the event falls outside the ride's GPS coverage).
 */
@Entity(
    tableName = "ride_events",
    foreignKeys = [
        ForeignKey(
            entity = Ride::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
data class RideEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long = 0,
    val type: RideEventType,
    val startOffsetMs: Long,
    val durationMs: Long,
    val peakG: Double,
    val peakJerkGPerS: Double,
    val avgG: Double,
    val speedMps: Double,
    val lat: Double? = null,
    val lon: Double? = null,
)
