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

@Database(entities = [Vehicle::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RidesafeDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao

    companion object {
        @Volatile private var instance: RidesafeDatabase? = null

        fun getInstance(context: Context): RidesafeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        RidesafeDatabase::class.java,
                        "ridesafe.db",
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
