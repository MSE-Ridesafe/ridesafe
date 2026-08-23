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

    @Query("SELECT * FROM refuels WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Refuel>

    /** Chronologically newest Refuel for a vehicle; id makes equal timestamps deterministic. */
    @Query("SELECT * FROM refuels WHERE vehicleId = :vehicleId ORDER BY timestampEpochMs DESC, id DESC LIMIT 1")
    suspend fun newestForVehicle(vehicleId: Long): Refuel?

    @Insert
    suspend fun insert(refuel: Refuel): Long

    @Update
    suspend fun update(refuel: Refuel)

    @Query("UPDATE refuels SET journeyAnchorRideId = :rideId WHERE id = :refuelId")
    suspend fun setJourneyAnchor(
        refuelId: Long,
        rideId: Long,
    )

    @Query("UPDATE refuels SET journeyAnchorRideId = NULL WHERE id IN (:refuelIds)")
    suspend fun clearJourneyAnchor(refuelIds: List<Long>)

    @Query("UPDATE refuels SET journeyAnchorRideId = NULL WHERE journeyAnchorRideId IN (:rideIds)")
    suspend fun clearJourneyAnchorsForRides(rideIds: List<Long>)
}
