package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedAddressDao {
    // Shortcuts (Home/Work/School) pinned first in that order, then custom places by label. kind is
    // stored as its enum name, so a plain "ORDER BY kind" would sort alphabetically — hence the CASE.
    @Query(
        "SELECT * FROM saved_addresses ORDER BY " +
            "CASE kind WHEN 'HOME' THEN 0 WHEN 'WORK' THEN 1 WHEN 'SCHOOL' THEN 2 ELSE 3 END, " +
            "label COLLATE NOCASE",
    )
    fun observeAll(): Flow<List<SavedAddress>>

    @Query("SELECT * FROM saved_addresses WHERE id = :id")
    fun observe(id: Long): Flow<SavedAddress?>

    /** One-shot read for the re-match pass. */
    @Query("SELECT * FROM saved_addresses")
    suspend fun all(): List<SavedAddress>

    @Insert
    suspend fun insert(address: SavedAddress): Long

    @Update
    suspend fun update(address: SavedAddress)

    @Delete
    suspend fun delete(address: SavedAddress)
}

/**
 * Re-match every ride's start/end point against the current saved addresses (ADR-07) and persist the
 * matched ids on the rides (the stored-match model). Run after any address add/edit/delete, and once
 * per launch to catch new recordings. Only changed rows are written.
 *
 * ponytail: O(rides × addresses) full scan on every address change — fine at personal scale; add a
 * spatial index or scope to affected rides if the ride count ever gets large.
 */
suspend fun rematchRides(
    rideDao: RideDao,
    addressDao: SavedAddressDao,
) {
    val addresses = addressDao.all()
    rideDao.all().forEach { ride ->
        val start = matchAddress(ride.startLat, ride.startLon, addresses)?.id
        val end = matchAddress(ride.endLat, ride.endLon, addresses)?.id
        if (start != ride.startAddressId || end != ride.endAddressId) {
            rideDao.setMatchedAddresses(ride.id, start, end)
        }
    }
}
