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

    @TypeConverter
    fun placeKindToString(value: SavedPlaceKind): String = value.name

    @TypeConverter
    fun stringToPlaceKind(value: String): SavedPlaceKind = SavedPlaceKind.valueOf(value)
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

/**
 * Adds start/end GPS position to rides, and makes distanceMeters/avgSpeedMps nullable (deferred to
 * the analysis pass, ANL-02). SQLite can't drop NOT NULL in place, so the table is recreated and
 * existing rows copied over; the new position columns default to null.
 */
private val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE rides_new (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "vehicleId INTEGER, " +
                    "startedAtEpochMs INTEGER NOT NULL, " +
                    "startedElapsedNanos INTEGER NOT NULL, " +
                    "endedAtEpochMs INTEGER, " +
                    "startLat REAL, " +
                    "startLon REAL, " +
                    "endLat REAL, " +
                    "endLon REAL, " +
                    "distanceMeters REAL, " +
                    "avgSpeedMps REAL, " +
                    "maxSpeedMps REAL NOT NULL, " +
                    "sampleFile TEXT NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO rides_new (id, vehicleId, startedAtEpochMs, startedElapsedNanos, " +
                    "endedAtEpochMs, distanceMeters, avgSpeedMps, maxSpeedMps, sampleFile) " +
                    "SELECT id, vehicleId, startedAtEpochMs, startedElapsedNanos, endedAtEpochMs, " +
                    "distanceMeters, avgSpeedMps, maxSpeedMps, sampleFile FROM rides",
            )
            db.execSQL("DROP TABLE rides")
            db.execSQL("ALTER TABLE rides_new RENAME TO rides")
        }
    }

/** Adds reverse-geocoded start/end address columns to rides (displayed, indexed, searched). */
private val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rides ADD COLUMN startAddress TEXT")
            db.execSQL("ALTER TABLE rides ADD COLUMN endAddress TEXT")
        }
    }

/**
 * Addresses are now built from the Geocoder result's structured fields and stored newline-separated
 * (street/place \n zip+city) instead of the raw comma-formatted line. Clear the old values so the
 * address backfill re-geocodes every ride into the new format (the data is derived, not user input).
 */
private val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE rides SET startAddress = NULL, endAddress = NULL")
        }
    }

/** Adds Ride.mergeGroupId (MRG-01) so rides can be merged into one trip; existing rides stay standalone (null). */
private val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rides ADD COLUMN mergeGroupId INTEGER")
        }
    }

/**
 * Adds saved addresses (DR-ADR) and the matched-address ids on rides (ADR-07). The new ride columns
 * default to null (unmatched); the re-match pass fills them from the saved addresses on next launch.
 */
/*
private val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rides ADD COLUMN startAddressId INTEGER")
            db.execSQL("ALTER TABLE rides ADD COLUMN endAddressId INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS saved_addresses (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "label TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "radiusMeters INTEGER NOT NULL, " +
                        "icon TEXT NOT NULL, " +
                        "address TEXT)",
            )
        }
    }*/

/**
 * Reconciles devices left on a v8 database with an *earlier, in-progress* v8 schema (an intermediate
 * build had been installed before the saved-address columns/table were finalized, so Room saw a
 * same-version schema-hash mismatch and refused to open). This bumps to v9 to force a migration that
 * normalizes both tables to the current shape **without losing recorded rides**:
 *
 * - `rides` is rebuilt to the exact target schema, copying every long-stable column (all present since
 *   v7) row-for-row; the derived match-id columns start null and are refilled by the re-match pass.
 * - `saved_addresses` is dropped and recreated to the exact target — it holds no data worth keeping
 *   yet (the feature is new), and recreating guarantees the columns match whatever the intermediate
 *   build wrote. Rides and vehicles keep all their data.
 *
 * A normal v7→v8→v9 upgrade also passes through here harmlessly (the copy re-copies; the recreate hits
 * an empty table).
 */
/*
private val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE rides_new (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "vehicleId INTEGER, " +
                        "startedAtEpochMs INTEGER NOT NULL, " +
                        "startedElapsedNanos INTEGER NOT NULL, " +
                        "endedAtEpochMs INTEGER, " +
                        "startLat REAL, " +
                        "startLon REAL, " +
                        "endLat REAL, " +
                        "endLon REAL, " +
                        "distanceMeters REAL, " +
                        "avgSpeedMps REAL, " +
                        "maxSpeedMps REAL NOT NULL, " +
                        "sampleFile TEXT NOT NULL, " +
                        "startAddress TEXT, " +
                        "endAddress TEXT, " +
                        "startAddressId INTEGER, " +
                        "endAddressId INTEGER)",
            )
            db.execSQL(
                "INSERT INTO rides_new (id, vehicleId, startedAtEpochMs, startedElapsedNanos, " +
                        "endedAtEpochMs, startLat, startLon, endLat, endLon, distanceMeters, avgSpeedMps, " +
                        "maxSpeedMps, sampleFile, startAddress, endAddress) " +
                        "SELECT id, vehicleId, startedAtEpochMs, startedElapsedNanos, endedAtEpochMs, " +
                        "startLat, startLon, endLat, endLon, distanceMeters, avgSpeedMps, maxSpeedMps, " +
                        "sampleFile, startAddress, endAddress FROM rides",
            )
            db.execSQL("DROP TABLE rides")
            db.execSQL("ALTER TABLE rides_new RENAME TO rides")

            db.execSQL("DROP TABLE IF EXISTS saved_addresses")
            db.execSQL(
                "CREATE TABLE saved_addresses (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "label TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "radiusMeters INTEGER NOT NULL, " +
                        "icon TEXT NOT NULL, " +
                        "address TEXT)",
            )
        }
    }*/


@Database(entities = [Vehicle::class, Ride::class, SavedAddress::class], version = 8, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RidesafeDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao

    abstract fun rideDao(): RideDao

    abstract fun savedAddressDao(): SavedAddressDao

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
                    ).build()
                    .also { instance = it }
            }
    }
}
