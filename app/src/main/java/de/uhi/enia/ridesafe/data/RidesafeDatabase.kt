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
const val RIDESAFE_DATABASE_VERSION = 18

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

    @TypeConverter
    fun rideEventTypeToString(value: RideEventType): String = value.name

    @TypeConverter
    fun stringToRideEventType(value: String): RideEventType = RideEventType.valueOf(value)
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
 * Adds saved addresses (DR-ADR) and the matched-address ids on rides (ADR-07), layered on top of the
 * ride-merging v8 schema (mergeGroupId). Additive only: the new ride columns default to null
 * (unmatched) and the re-match pass fills them from the saved addresses on next launch. Existing
 * rides and vehicles are untouched.
 */
private val MIGRATION_8_9 =
    object : Migration(8, 9) {
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
    }

/**
 * Adds the driving-event table (ANL-01) and Ride.analyzerVersion. Additive only: existing rides get
 * a null version, which the backfill pass reads as "never analyzed" and fills from the raw sample
 * files that have been recorded all along — nothing needs re-recording. The index name has to match
 * what Room generates for `@Index("rideId")`, or its schema validation rejects the table on open.
 */
private val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rides ADD COLUMN analyzerVersion INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS drive_events (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "rideId INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "startOffsetMs INTEGER NOT NULL, " +
                    "durationMs INTEGER NOT NULL, " +
                    "peakG REAL NOT NULL, " +
                    "avgG REAL NOT NULL, " +
                    "speedMps REAL NOT NULL, " +
                    "lat REAL, " +
                    "lon REAL, " +
                    "FOREIGN KEY(rideId) REFERENCES rides(id) ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_events_rideId ON drive_events(rideId)")
        }
    }

/**
 * Adds DriveEvent.peakJerkGPerS, which detection now triggers on rather than force alone. The table
 * is dropped and recreated rather than ALTER-ed: every stored event was produced by a detector that
 * didn't measure jerk, so all of them are stale by definition, and the ANALYZER_VERSION bump has the
 * backfill regenerate the lot from the raw sample files. Nothing here is a source of truth.
 */
private val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS drive_events")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS drive_events (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "rideId INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "startOffsetMs INTEGER NOT NULL, " +
                    "durationMs INTEGER NOT NULL, " +
                    "peakG REAL NOT NULL, " +
                    "peakJerkGPerS REAL NOT NULL, " +
                    "avgG REAL NOT NULL, " +
                    "speedMps REAL NOT NULL, " +
                    "lat REAL, " +
                    "lon REAL, " +
                    "FOREIGN KEY(rideId) REFERENCES rides(id) ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_events_rideId ON drive_events(rideId)")
        }
    }

/**
 * Renames drive_events to ride_events, following the entity rename. A pure rename rather than a
 * drop-and-recreate: the events are regenerable, but regenerating them means re-reading every ride's
 * raw samples, and a long ride is minutes of work for a change that alters no data.
 *
 * The index needs recreating even though SQLite carries it across the rename, because it carries the
 * *old name* with it while Room derives the name it expects from the table. Leaving it would fail
 * schema validation on open with an index-name mismatch rather than anything obvious.
 *
 * Earlier migrations deliberately still say drive_events. They describe the schema as it stood at
 * their version, and a device coming from v9 walks all of them in order — renaming them there would
 * leave this one looking for a table that was never created.
 */
private val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE drive_events RENAME TO ride_events")
            db.execSQL("DROP INDEX IF EXISTS index_drive_events_rideId")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ride_events_rideId ON ride_events(rideId)")
        }
    }

/**
 * Moves analysis bookkeeping out of the rides table and into `ride_analysis`, one row per (ride,
 * step), so the pipeline can re-derive one step without invalidating the rest.
 *
 * The seeds matter more than the table: they carry today's state across, so upgrading re-analyzes
 * nothing that is already current. Route work is seeded from `distanceMeters`, which only the
 * processing pass ever set, and the event steps from the `analyzerVersion` being retired — a ride
 * stamped below the current detector stays stale and is picked up as it would have been anyway.
 *
 * The axis step has no stored output, so its seed is purely a marker; it is seeded wherever the
 * events it feeds are current. A ride seeded route-current whose sidecar has since been pruned is
 * caught at run time, when restoring it fails and the step is re-derived.
 *
 * minSdk 34 means SQLite 3.39+, so the column goes with a plain DROP COLUMN — no table rebuild.
 */
private val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS ride_analysis (" +
                    "rideId INTEGER NOT NULL, " +
                    "stage TEXT NOT NULL, " +
                    "version INTEGER NOT NULL, " +
                    "PRIMARY KEY(rideId, stage), " +
                    "FOREIGN KEY(rideId) REFERENCES rides(id) ON DELETE CASCADE)",
            )
            db.execSQL(
                "INSERT INTO ride_analysis SELECT id, 'route', 2 FROM rides WHERE distanceMeters IS NOT NULL",
            )
            db.execSQL(
                "INSERT INTO ride_analysis SELECT id, 'axis', 1 FROM rides WHERE analyzerVersion >= 8",
            )
            db.execSQL(
                "INSERT INTO ride_analysis SELECT id, 'events', analyzerVersion FROM rides " +
                    "WHERE analyzerVersion IS NOT NULL",
            )
            db.execSQL("ALTER TABLE rides DROP COLUMN analyzerVersion")
        }
    }

/** Adds independently persisted refueling history without modifying any existing table or row. */
val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS refuels (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "vehicleId INTEGER NOT NULL, " +
                    "timestampEpochMs INTEGER NOT NULL, " +
                    "fuelAmountMilliliters INTEGER NOT NULL, " +
                    "totalPriceMinor INTEGER NOT NULL, " +
                    "currencyCode TEXT NOT NULL, " +
                    "odometerMeters INTEGER NOT NULL, " +
                    "stationAddress TEXT, " +
                    "isFullTank INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_refuels_timestampEpochMs ON refuels(timestampEpochMs)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_refuels_vehicleId ON refuels(vehicleId)")
        }
    }

/** Adds an optional physical-ride anchor used to resolve a Refuel's current logical journey. */
val MIGRATION_14_15 =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE refuels ADD COLUMN journeyAnchorRideId INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_refuels_journeyAnchorRideId ON refuels(journeyAnchorRideId)",
            )
        }
    }

/** Allows a Refuel station to reference a Saved Place while preserving custom station text. */
val MIGRATION_15_16 =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE refuels ADD COLUMN stationSavedAddressId INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_refuels_stationSavedAddressId ON refuels(stationSavedAddressId)",
            )
        }
    }

/** Removes obsolete Refuel station/address data while preserving every fuel and cost field. */
val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS index_refuels_stationSavedAddressId")
            db.execSQL("ALTER TABLE refuels DROP COLUMN stationSavedAddressId")
            db.execSQL("ALTER TABLE refuels DROP COLUMN stationAddress")
        }
    }

/** Adds archive-stable vehicle identity and modification time without changing any relationships. */
val MIGRATION_17_18 =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE vehicles ADD COLUMN vehicleUuid TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE vehicles ADD COLUMN updatedAtEpochMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE vehicles SET vehicleUuid = lower(" +
                    "hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || " +
                    "substr(hex(randomblob(2)), 2) || '-' || " +
                    "substr('89ab', abs(random() % 4) + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || " +
                    "hex(randomblob(6))) WHERE vehicleUuid = ''",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_vehicles_vehicleUuid ON vehicles(vehicleUuid)",
            )
        }
    }

@Database(
    entities = [
        Vehicle::class,
        Ride::class,
        SavedAddress::class,
        RideEvent::class,
        RideAnalysisState::class,
        Refuel::class,
    ],
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
                    ).build()
                    .also { instance = it }
            }
    }
}
