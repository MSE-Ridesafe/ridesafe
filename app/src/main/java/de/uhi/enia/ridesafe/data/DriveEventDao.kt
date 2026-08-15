package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveEventDao {
    /** One ride's events, oldest first — the map marker layer and the detail list read this. */
    @Query("SELECT * FROM drive_events WHERE rideId = :rideId ORDER BY startOffsetMs ASC")
    fun observeForRide(rideId: Long): Flow<List<DriveEvent>>

    /** Every stop's events for a merged ride (MRG-07), so the merged map shows the whole trip. */
    @Query(
        "SELECT e.* FROM drive_events e JOIN rides r ON e.rideId = r.id " +
            "WHERE r.mergeGroupId = :groupId ORDER BY r.startedAtEpochMs ASC, e.startOffsetMs ASC",
    )
    fun observeForGroup(groupId: Long): Flow<List<DriveEvent>>

    @Insert
    suspend fun insertAll(events: List<DriveEvent>)

    @Query("DELETE FROM drive_events WHERE rideId = :rideId")
    suspend fun deleteForRide(rideId: Long)

    /**
     * Replace a ride's events and stamp the detector version that produced them, as one transaction
     * so a re-analysis can't leave a ride holding a mix of two detectors' output.
     */
    @Transaction
    suspend fun replaceForRide(
        rideId: Long,
        analyzerVersion: Int,
        events: List<DriveEvent>,
    ) {
        deleteForRide(rideId)
        insertAll(events)
        markAnalyzed(rideId, analyzerVersion)
    }

    @Query("UPDATE rides SET analyzerVersion = :analyzerVersion WHERE id = :rideId")
    suspend fun markAnalyzed(
        rideId: Long,
        analyzerVersion: Int,
    )

    /**
     * Finished rides whose events are missing or were produced by an older detector. Rides that
     * recorded no GPS are skipped so we don't keep re-reading a sample file that can't yield events.
     */
    @Query(
        "SELECT * FROM rides WHERE endedAtEpochMs IS NOT NULL AND startLat IS NOT NULL " +
            "AND (analyzerVersion IS NULL OR analyzerVersion < :currentVersion)",
    )
    suspend fun needingAnalysis(currentVersion: Int): List<Ride>
}
