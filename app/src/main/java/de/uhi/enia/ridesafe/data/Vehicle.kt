package de.uhi.enia.ridesafe.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A vehicle in the user's garage (entity DR-VEH).
 *
 * Odometer is stored canonically in kilometers ([mileageKm]); display converts via
 * [de.uhi.enia.ridesafe.util.formatDistance]. [year], [fuelEconomy] and [tankSize]
 * are optional — the analytics features that need them don't exist yet.
 */
@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val make: String,
    val model: String,
    val licensePlate: String,
    val fuelType: FuelType,
    val mileageKm: Int,
    val isPrimary: Boolean = false,
    val year: Int? = null,
    val fuelEconomy: Double? = null,
    val tankSize: Double? = null,
)

/** Stored by [name]; user-facing labels are localized in the UI layer. */
enum class FuelType {
    PETROL,
    DIESEL,
    ELECTRIC,
    HYBRID,
    LPG,
    OTHER,
}
