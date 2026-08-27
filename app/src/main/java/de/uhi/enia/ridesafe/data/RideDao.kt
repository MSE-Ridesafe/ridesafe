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

    /** One-shot read of every ride for the saved-address re-match pass (ADR-07). */
    @Query("SELECT * FROM rides")
    suspend fun all(): List<Ride>

    /** Targeted snapshot read for exports; callers restore their own logical order after this query. */
    @Query("SELECT * FROM rides WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Ride>

    /** The stops of a merged ride (MRG-01), in chronological order — the merged detail's source of truth. */
    @Query("SELECT * FROM rides WHERE mergeGroupId = :groupId ORDER BY startedAtEpochMs ASC")
    fun observeGroup(groupId: Long): Flow<List<Ride>>

    /** Non-observing read of a group's current stops, for the post-unmerge cleanup. */
    @Query("SELECT * FROM rides WHERE mergeGroupId = :groupId")
    suspend fun groupMembers(groupId: Long): List<Ride>

    @Query("SELECT * FROM rides WHERE mergeGroupId IN (:groupIds)")
    suspend fun membersOfGroups(groupIds: List<Long>): List<Ride>

    /** Tag rides with a merge group id (MRG-01), or clear it (null) to un-merge them (MRG-03). */
    @Query("UPDATE rides SET mergeGroupId = :groupId WHERE id IN (:ids)")
    suspend fun setMergeGroup(
        groupId: Long?,
        ids: List<Long>,
    )

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

    /**
     * Finished rides that recorded a fix — every candidate for the GPS-processing backfill. Which of
     * them actually needs the work is decided by the caller on the presence of a current-version
     * route sidecar, not by distanceMeters: a ride processed by an older, worse filter has both a
     * stale route *and* a wrong distance, so "already has a distance" is not the same as "done".
     * No-GPS rides (startLat null) are skipped so we don't keep re-reading their sample file.
     */
    @Query("SELECT * FROM rides WHERE endedAtEpochMs IS NOT NULL AND startLat IS NOT NULL")
    suspend fun processable(): List<Ride>

    /**
     * Replace an endpoint the recording got wrong, and drop everything derived from it.
     *
     * Recording stores the raw first/last fix, and those are exactly where the fused provider is
     * most likely to have been guessing — a ride can start hundreds of meters from where it really
     * did. The Kalman pass rejects such a fix outright, so the filtered track's first point is the
     * honest one. Its address and matched saved place were derived from the bad coordinate, so they
     * are cleared here rather than left to disagree with the position they claim to describe; the
     * geocode and re-match passes fill them back in.
     */
    @Query(
        "UPDATE rides SET startLat = :lat, startLon = :lon, startAddress = NULL, startAddressId = NULL " +
            "WHERE id = :id",
    )
    suspend fun correctStart(
        id: Long,
        lat: Double,
        lon: Double,
    )

    /** The end-of-ride counterpart of [correctStart]. */
    @Query(
        "UPDATE rides SET endLat = :lat, endLon = :lon, endAddress = NULL, endAddressId = NULL " +
            "WHERE id = :id",
    )
    suspend fun correctEnd(
        id: Long,
        lat: Double,
        lon: Double,
    )

    /** Store the distance + average speed the processing pass computed from the filtered track (ANL-02). */
    @Query("UPDATE rides SET distanceMeters = :distanceMeters, avgSpeedMps = :avgSpeedMps WHERE id = :id")
    suspend fun setMetrics(
        id: Long,
        distanceMeters: Double,
        avgSpeedMps: Double,
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

    /** Store the saved addresses the start/end points matched (ADR-07); either may be null. */
    @Query("UPDATE rides SET startAddressId = :startAddressId, endAddressId = :endAddressId WHERE id = :id")
    suspend fun setMatchedAddresses(
        id: Long,
        startAddressId: Long?,
        endAddressId: Long?,
    )

    /** Drop a ride the recorder decided not to keep (TRK-10); its sample file goes with it. */
    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM rides WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Delete
    suspend fun delete(ride: Ride)
}
