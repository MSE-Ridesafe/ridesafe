package de.uhi.enia.ridesafe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A vehicle in the user's garage (entity DR-VEH).
 *
 * Odometer is stored canonically in kilometers ([mileageKm]); display converts via
 * [de.uhi.enia.ridesafe.util.formatDistance]. [name] (an optional nickname), [year],
 * [fuelEconomy] and [tankSize] are optional — blank/null means "not set". The nickname
 * is rendered via [de.uhi.enia.ridesafe.ui.screens.garage.displayTitle].
 */
@Entity(
    tableName = "vehicles",
    indices = [Index(value = ["vehicleUuid"], unique = true)],
)
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val make: String,
    val model: String,
    val licensePlate: String,
    val fuelType: FuelType,
    val mileageKm: Int,
    val isPrimary: Boolean = false,
    // Bluetooth devices mapped to this vehicle for auto-tracking (GAR-08, TRK-08), stored as a
    // JSON list in one column — a tiny owned collection, not worth a separate table + relation.
    @ColumnInfo(defaultValue = "[]") val bluetoothDevices: List<BtDevice> = emptyList(),
    val year: Int? = null,
    val fuelEconomy: Double? = null,
    val tankSize: Double? = null,
    val vehicleType: String? = null,
    val engine: String? = null,
    val manufacturingCountry: String? = null,
    /** Stable identity used to recognize this vehicle across RideSafe backup generations. */
    @ColumnInfo(defaultValue = "''") val vehicleUuid: String = UUID.randomUUID().toString(),
    /** Last user/data update; import conflict resolution never relies on database numeric IDs. */
    @ColumnInfo(defaultValue = "0") val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * A Bluetooth device mapped to a vehicle. [address] is the MAC used to match connections;
 * [name] is the friendly name, captured at mapping time for display.
 */
@Serializable
data class BtDevice(
    val address: String,
    val name: String,
)

/** Stored by [name]; user-facing labels are localized in the UI layer. */
enum class FuelType {
    UNSPECIFIED,
    PETROL,
    DIESEL,
    ELECTRIC,
    HYBRID,
    LPG,
    OTHER,
}
