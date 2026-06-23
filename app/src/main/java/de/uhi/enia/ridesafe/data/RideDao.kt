package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<Ride>>

    @Query("SELECT * FROM rides WHERE id = :id")
    fun observe(id: Long): Flow<Ride?>

    /** Rides that never got an end timestamp — left open by a crash/kill; recovery finalizes them (NFR-06). */
    @Query("SELECT * FROM rides WHERE endedAtEpochMs IS NULL")
    suspend fun dangling(): List<Ride>

    @Insert
    suspend fun insert(ride: Ride): Long

    /** Finalize a ride once recording stops (end time + computed summary). */
    @Query(
        "UPDATE rides SET endedAtEpochMs = :endedAtEpochMs, distanceMeters = :distanceMeters, " +
            "avgSpeedMps = :avgSpeedMps, maxSpeedMps = :maxSpeedMps WHERE id = :id",
    )
    suspend fun finalize(
        id: Long,
        endedAtEpochMs: Long,
        distanceMeters: Double,
        avgSpeedMps: Double,
        maxSpeedMps: Double,
    )

    @Delete
    suspend fun delete(ride: Ride)
}
