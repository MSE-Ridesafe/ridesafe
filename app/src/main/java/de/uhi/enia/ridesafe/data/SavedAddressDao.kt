package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.uhi.enia.ridesafe.util.haversineMeters
import kotlinx.coroutines.flow.Flow
import java.util.Locale

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

    /** Focused batch read for resolving actual endpoint addresses during ride export. */
    @Query("SELECT * FROM saved_addresses WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<SavedAddress>

    @Insert
    suspend fun insert(address: SavedAddress): Long

    @Update
    suspend fun update(address: SavedAddress)

    @Delete
    suspend fun delete(address: SavedAddress)

    @Query("DELETE FROM saved_addresses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

/**
 * Repairs duplicate places produced by older importers. Call inside a database transaction so a
 * ride can never observe its address removed before its reference has been repointed.
 */
suspend fun consolidateSavedAddressDuplicates(
    rideDao: RideDao,
    addressDao: SavedAddressDao,
) {
    val retained = mutableListOf<SavedAddress>()
    addressDao.all().sortedBy(SavedAddress::id).forEach { candidate ->
        val canonical = retained.firstOrNull { it.isEquivalentSavedPlace(candidate) }
        if (canonical == null) {
            retained += candidate
        } else {
            rideDao.replaceSavedAddressReferences(candidate.id, canonical.id)
            addressDao.deleteByIds(listOf(candidate.id))
        }
    }
}

private val singletonPlaceKinds =
    setOf(SavedPlaceKind.HOME, SavedPlaceKind.WORK, SavedPlaceKind.SCHOOL)

private fun SavedAddress.isEquivalentSavedPlace(other: SavedAddress): Boolean {
    if (kind != other.kind) return false
    if (kind in singletonPlaceKinds) return true
    if (normalizePlaceText(label) != normalizePlaceText(other.label)) return false

    val firstAddress = address?.let(::normalizePlaceText).orEmpty()
    val secondAddress = other.address?.let(::normalizePlaceText).orEmpty()
    val sameKnownAddress = firstAddress.isNotEmpty() && firstAddress == secondAddress
    val sameCoordinates = haversineMeters(latitude, longitude, other.latitude, other.longitude) <= 15.0
    return sameKnownAddress || sameCoordinates
}

private fun normalizePlaceText(value: String): String = value.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)

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
