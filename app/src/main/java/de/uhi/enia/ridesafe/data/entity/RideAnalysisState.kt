package de.uhi.enia.ridesafe.data.entity

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Which build of which analysis step last ran for a ride — the pipeline's staleness bookkeeping
 * (see RideAnalysisPipeline). One row per (ride, stage): its absence means the stage never ran, a
 * [version] below the stage's current one means it ran under an older build and is due again.
 *
 * Per stage rather than one version per ride, so a step can be re-derived on its own. Bumping the
 * safety score's version, say, re-derives scores from the stored events without re-reading a single
 * sample file — where a whole-ride version would drag every ride's raw samples back off disk.
 *
 * Derived and disposable: every stage can regenerate its output from the raw sample file, so
 * dropping rows here costs time, never data. Rows die with their ride (CASCADE).
 */
@Entity(
    tableName = "ride_analysis",
    primaryKeys = ["rideId", "stage"],
    foreignKeys = [
        ForeignKey(
            entity = Ride::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RideAnalysisState(
    val rideId: Long,
    /** The stage's stable id ([de.uhi.enia.ridesafe.analysis.RideStage.id]); renaming one needs a migration. */
    val stage: String,
    val version: Int,
)

@Dao
interface RideAnalysisDao {
    /** Everything recorded for one ride, as `stage -> version`; the pipeline plans off this. */
    @Query("SELECT * FROM ride_analysis WHERE rideId = :rideId")
    suspend fun forRide(rideId: Long): List<RideAnalysisState>

    /** Stamp a stage as done at [RideAnalysisState.version]; replaces any earlier stamp. */
    @Upsert
    suspend fun stamp(state: RideAnalysisState)

    /**
     * The whole table, for the backfill to diff against the current stages in one go. Deliberately
     * not a "rides needing analysis" query: staleness is a rule about stage versions *and* their
     * dependencies, it already lives in the pipeline's planner, and restating half of it in SQL is
     * how the two drift apart. Three rows per ride keeps this small enough not to care.
     */
    @Query("SELECT * FROM ride_analysis")
    suspend fun all(): List<RideAnalysisState>

    @Query("SELECT * FROM ride_analysis WHERE rideId IN (:rideIds) ORDER BY rideId ASC, stage ASC")
    suspend fun forRides(rideIds: List<Long>): List<RideAnalysisState>
}
