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

    /**
     * Finalize a ride once recording stops: end time, start/end position, and max speed.
     * Distance and avg speed stay null here — the analysis pass over the sample file fills them.
     */
    @Query(
        "UPDATE rides SET endedAtEpochMs = :endedAtEpochMs, startLat = :startLat, startLon = :startLon, " +
            "endLat = :endLat, endLon = :endLon, maxSpeedMps = :maxSpeedMps WHERE id = :id",
    )
    suspend fun finalize(
        id: Long,
        endedAtEpochMs: Long,
        startLat: Double?,
        startLon: Double?,
        endLat: Double?,
        endLon: Double?,
        maxSpeedMps: Double,
    )

    /** Rides with a fix but no reverse-geocoded address yet — the address backfill targets these. */
    @Query(
        "SELECT * FROM rides WHERE (startLat IS NOT NULL AND startAddress IS NULL) " +
            "OR (endLat IS NOT NULL AND endAddress IS NULL)",
    )
    suspend fun needingAddresses(): List<Ride>

    /** Store the reverse-geocoded start/end addresses (DR-RID); either may be null if it failed. */
    @Query("UPDATE rides SET startAddress = :startAddress, endAddress = :endAddress WHERE id = :id")
    suspend fun setAddresses(
        id: Long,
        startAddress: String?,
        endAddress: String?,
    )

    @Delete
    suspend fun delete(ride: Ride)
}
