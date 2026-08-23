package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RefuelDao {
    @Query("SELECT * FROM refuels ORDER BY timestampEpochMs DESC, id DESC")
    fun observeAll(): Flow<List<Refuel>>

    @Insert
    suspend fun insert(refuel: Refuel): Long
}
