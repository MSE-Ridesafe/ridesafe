package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RideEventDao {
    /** One ride's events, oldest first — the map marker layer and the detail list read this. */
    @Query("SELECT * FROM ride_events WHERE rideId = :rideId ORDER BY startOffsetMs ASC")
    fun observeForRide(rideId: Long): Flow<List<RideEvent>>

    /** Every stop's events for a merged ride (MRG-07), so the merged map shows the whole trip. */
    @Query(
        "SELECT e.* FROM ride_events e JOIN rides r ON e.rideId = r.id " +
            "WHERE r.mergeGroupId = :groupId ORDER BY r.startedAtEpochMs ASC, e.startOffsetMs ASC",
    )
    fun observeForGroup(groupId: Long): Flow<List<RideEvent>>

    /** Ride ids with at least one detected event (ANL-01) — the logbook's "has events" filter. */
    @Query("SELECT DISTINCT rideId FROM ride_events")
    fun observeRideIdsWithEvents(): Flow<List<Long>>

    @Insert
    suspend fun insertAll(events: List<RideEvent>)

    @Query("DELETE FROM ride_events WHERE rideId = :rideId")
    suspend fun deleteForRide(rideId: Long)

    /**
     * Replace a ride's events as one transaction, so a re-analysis can't leave a ride holding a mix
     * of two detectors' output. Which detector build produced them is stamped separately, by the
     * pipeline into `ride_analysis`, alongside every other analysis step's version.
     */
    @Transaction
    suspend fun replaceForRide(
        rideId: Long,
        events: List<RideEvent>,
    ) {
        deleteForRide(rideId)
        insertAll(events)
    }

    /** One ride's stored events, for an analysis step deriving from them rather than from samples. */
    @Query("SELECT * FROM ride_events WHERE rideId = :rideId ORDER BY startOffsetMs ASC")
    suspend fun eventsFor(rideId: Long): List<RideEvent>
}
