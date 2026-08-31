package de.uhi.enia.ridesafe.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.uhi.enia.ridesafe.data.entity.SavedAddress
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
