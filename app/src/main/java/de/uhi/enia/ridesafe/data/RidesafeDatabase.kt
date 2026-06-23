package de.uhi.enia.ridesafe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json

private val deviceJson = Json { ignoreUnknownKeys = true }

class Converters {
    @TypeConverter
    fun fuelTypeToString(value: FuelType): String = value.name

    @TypeConverter
    fun stringToFuelType(value: String): FuelType = FuelType.valueOf(value)

    @TypeConverter
    fun devicesToString(value: List<BtDevice>): String = deviceJson.encodeToString(value)

    @TypeConverter
    fun stringToDevices(value: String): List<BtDevice> = if (value.isBlank()) emptyList() else deviceJson.decodeFromString(value)
}

/** Adds Vehicle.bluetoothAddresses (GAR-08) without dropping existing vehicles (NFR-06). */
private val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE vehicles ADD COLUMN bluetoothAddresses TEXT NOT NULL DEFAULT ''")
        }
    }

/**
 * Replaces the address-only column with one that also stores the device name. Any existing
 * mappings (addresses only) are dropped — the user re-links once to capture the names; the
 * vehicles themselves are untouched.
 */
private val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE vehicles DROP COLUMN bluetoothAddresses")
            db.execSQL("ALTER TABLE vehicles ADD COLUMN bluetoothDevices TEXT NOT NULL DEFAULT '[]'")
        }
    }

/** Adds the rides table (DR-RID) for ride recording; vehicles are untouched. */
private val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS rides (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "vehicleId INTEGER, " +
                    "startedAtEpochMs INTEGER NOT NULL, " +
                    "startedElapsedNanos INTEGER NOT NULL, " +
                    "endedAtEpochMs INTEGER, " +
                    "distanceMeters REAL NOT NULL, " +
                    "avgSpeedMps REAL NOT NULL, " +
                    "maxSpeedMps REAL NOT NULL, " +
                    "sampleFile TEXT NOT NULL)",
            )
        }
    }

@Database(entities = [Vehicle::class, Ride::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RidesafeDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao

    abstract fun rideDao(): RideDao

    companion object {
        @Volatile private var instance: RidesafeDatabase? = null

        fun getInstance(context: Context): RidesafeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        RidesafeDatabase::class.java,
                        "ridesafe.db",
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
