package de.uhi.enia.ridesafe.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class VehicleDao {
    // Sort by make/model (shown as the list title); name is an optional nickname now.
    @Query("SELECT * FROM vehicles ORDER BY isPrimary DESC, make COLLATE NOCASE, model COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    abstract fun observe(id: Long): Flow<Vehicle?>

    @Query("SELECT COUNT(*) FROM vehicles")
    abstract suspend fun count(): Int

    @Insert
    abstract suspend fun insert(vehicle: Vehicle): Long

    @Update
    abstract suspend fun update(vehicle: Vehicle)

    @Delete
    abstract suspend fun delete(vehicle: Vehicle)

    @Query("UPDATE vehicles SET isPrimary = 0")
    abstract suspend fun clearPrimary()

    /** Oldest remaining vehicle (lowest id), or null when the garage is empty. */
    @Query("SELECT id FROM vehicles ORDER BY id LIMIT 1")
    abstract suspend fun firstVehicleId(): Long?

    @Query("UPDATE vehicles SET isPrimary = 1 WHERE id = :id")
    abstract suspend fun setPrimary(id: Long)

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

    /**
     * Update a vehicle, preserving GAR-07. The form only ever lets [makePrimary] go from
     * false→true (you promote another vehicle rather than demote the current primary), so
     * promoting atomically unsets the previous primary; otherwise the flag is left as-is.
     */
    @Transaction
    open suspend fun updateVehicle(
        vehicle: Vehicle,
        makePrimary: Boolean,
    ) {
        if (makePrimary) clearPrimary()
        update(vehicle.copy(isPrimary = makePrimary || vehicle.isPrimary))
    }

    /**
     * Delete a vehicle (GAR-04). If it was the primary, promote the oldest remaining
     * vehicle so the "exactly one primary" invariant (GAR-07) still holds.
     */
    @Transaction
    open suspend fun deleteVehicle(vehicle: Vehicle) {
        delete(vehicle)
        if (vehicle.isPrimary) {
            firstVehicleId()?.let { setPrimary(it) }
        }
    }
}
