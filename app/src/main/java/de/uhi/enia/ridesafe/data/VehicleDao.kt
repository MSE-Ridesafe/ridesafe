package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY isPrimary DESC, name COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    abstract fun observe(id: Long): Flow<Vehicle?>

    @Query("SELECT COUNT(*) FROM vehicles")
    abstract suspend fun count(): Int

    @Insert
    abstract suspend fun insert(vehicle: Vehicle): Long

    @Query("UPDATE vehicles SET isPrimary = 0")
    abstract suspend fun clearPrimary()

    /**
     * Insert a vehicle, keeping the "exactly one primary" invariant (GAR-07).
     * The very first vehicle becomes primary automatically; otherwise [makePrimary]
     * decides, and a new primary atomically unsets the previous one.
     */
    @Transaction
    open suspend fun addVehicle(
        vehicle: Vehicle,
        makePrimary: Boolean,
    ): Long {
        val primary = makePrimary || count() == 0
        if (primary) clearPrimary()
        return insert(vehicle.copy(isPrimary = primary))
    }
}
