package de.uhi.enia.ridesafe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.json.Json

const val RIDESAFE_DATABASE_VERSION = 23

/** JSON for the small owned values kept in a single column (BT devices, eco, dynamics, score). */
private val columnJson = Json { ignoreUnknownKeys = true }

class Converters {
    @TypeConverter
    fun fuelTypeToString(value: FuelType): String = value.name

    @TypeConverter
    fun stringToFuelType(value: String): FuelType = FuelType.valueOf(value)

    @TypeConverter
    fun devicesToString(value: List<BtDevice>): String = columnJson.encodeToString(value)

    @TypeConverter
    fun stringToDevices(value: String): List<BtDevice> = if (value.isBlank()) emptyList() else columnJson.decodeFromString(value)

    @TypeConverter
    fun placeKindToString(value: SavedPlaceKind): String = value.name

    @TypeConverter
    fun stringToPlaceKind(value: String): SavedPlaceKind = SavedPlaceKind.valueOf(value)

    @TypeConverter
    fun rideEventTypeToString(value: RideEventType): String = value.name

    @TypeConverter
    fun stringToRideEventType(value: String): RideEventType = RideEventType.valueOf(value)

    @TypeConverter
    fun ecoToString(value: RideEco?): String? = value?.let { columnJson.encodeToString(it) }

    // A blob written by a build whose RideEco had different fields reads as "no profile", and the
    // pipeline derives it again — cheaper than a migration for a value that is regenerable anyway.
    @TypeConverter
    fun stringToEco(value: String?): RideEco? = value?.let { runCatching { columnJson.decodeFromString<RideEco>(it) }.getOrNull() }

    @TypeConverter
    fun dynamicsToString(value: RideDynamics?): String? = value?.let { columnJson.encodeToString(it) }

    @TypeConverter
    fun stringToDynamics(value: String?): RideDynamics? =
        value?.let { runCatching { columnJson.decodeFromString<RideDynamics>(it) }.getOrNull() }

    @TypeConverter
    fun scoreToString(value: SafetyScore?): String? = value?.let { columnJson.encodeToString(it) }

    @TypeConverter
    fun stringToScore(value: String?): SafetyScore? =
        value?.let { runCatching { columnJson.decodeFromString<SafetyScore>(it) }.getOrNull() }
}

@Database(
    entities = [Vehicle::class, Ride::class, SavedAddress::class, RideEvent::class, RideAnalysisState::class, Refuel::class],
    version = RIDESAFE_DATABASE_VERSION,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class RidesafeDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao

    abstract fun rideDao(): RideDao

    abstract fun savedAddressDao(): SavedAddressDao

    abstract fun rideEventDao(): RideEventDao

    abstract fun rideAnalysisDao(): RideAnalysisDao

    abstract fun refuelDao(): RefuelDao

    companion object {
        @Volatile private var instance: RidesafeDatabase? = null

        fun getInstance(context: Context): RidesafeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        RidesafeDatabase::class.java,
                        "ridesafe.db",
                    ).addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                    ).build()
                    .also { instance = it }
            }
    }
}
