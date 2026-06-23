package de.uhi.enia.ridesafe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A vehicle in the user's garage (entity DR-VEH).
 *
 * Odometer is stored canonically in kilometers ([mileageKm]); display converts via
 * [de.uhi.enia.ridesafe.util.formatDistance]. [name] (an optional nickname), [year],
 * [fuelEconomy] and [tankSize] are optional — blank/null means "not set". The nickname
 * is rendered via [de.uhi.enia.ridesafe.ui.screens.garage.displayTitle].
 */
@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val make: String,
    val model: String,
    val licensePlate: String,
    val fuelType: FuelType,
    val mileageKm: Int,
    val isPrimary: Boolean = false,
    // MAC addresses of Bluetooth devices mapped to this vehicle for auto-tracking (GAR-08, TRK-08).
    @ColumnInfo(defaultValue = "") val bluetoothAddresses: Set<String> = emptySet(),
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
