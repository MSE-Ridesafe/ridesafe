package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RefuelDao {
    @Query("SELECT * FROM refuels ORDER BY timestampEpochMs DESC, id DESC")
    fun observeAll(): Flow<List<Refuel>>

    @Query("SELECT * FROM refuels WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Refuel?

    @Insert
    suspend fun insert(refuel: Refuel): Long

    @Update
    suspend fun update(refuel: Refuel)
}
