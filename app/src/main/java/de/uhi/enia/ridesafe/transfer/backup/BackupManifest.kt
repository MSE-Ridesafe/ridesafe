package de.uhi.enia.ridesafe.transfer.backup

import de.uhi.enia.ridesafe.analysis.ROUTE_VERSION
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val RIDE_BACKUP_FORMAT_ID = "de.uhi.enia.ridesafe.selected-rides"
internal const val RIDE_BACKUP_FORMAT_VERSION = 1
internal const val RIDE_BACKUP_SCHEMA_VERSION = 2
internal const val MANIFEST_ENTRY = "manifest.json"
internal const val RAW_ROLE = "raw_samples"
internal const val ROUTE_ROLE = "processed_route"
internal const val REQUIRED_SOURCE = "required_source"
internal const val OPTIONAL_DERIVED = "optional_regenerable_derived"
internal const val INCLUDED = "included"
internal const val ABSENT = "absent"

internal val backupJson =
    Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        classDiscriminator = "ty"
    }

@Serializable
internal data class RideBackupManifest(
    val formatId: String = RIDE_BACKUP_FORMAT_ID,
    val formatVersion: Int = RIDE_BACKUP_FORMAT_VERSION,
    val schemaVersion: Int = RIDE_BACKUP_SCHEMA_VERSION,
    val createdAtEpochMs: Long,
    val producer: BackupProducer,
    /** Diagnostic only. It never controls archive-schema compatibility. */
    val sourceDatabaseVersion: Int,
    val processingVersions: BackupProcessingVersions,
    val contract: BackupContract = BackupContract(),
    val logicalSelections: List<BackupLogicalSelection>,
    val mergeGroups: List<BackupMergeGroup>,
    val rides: List<BackupRide>,
    val vehicles: List<BackupVehicle>,
    val savedAddresses: List<BackupSavedAddress>,
    val rideEvents: List<BackupRideEvent>,
    val analysisStates: List<BackupAnalysisState>,
    val refuels: List<BackupRefuel>,
    val files: List<BackupFile>,
)

@Serializable
internal data class BackupProducer(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val platform: String,
    val platformVersion: String,
    val platformApiLevel: Int,
)

@Serializable
internal data class BackupProcessingVersions(
    val route: Int,
    val axis: Int,
    val events: Int,
    val endpoints: Int,
)

@Serializable
internal data class BackupContract(
    val numericIdsAreArchiveLocal: Boolean = true,
    val importerMustRemapIds: Boolean = true,
    val rawNdjsonRecordsGloballyTimestampOrdered: Boolean = false,
    val unknownFields: String = "ignore",
    val unknownEnumValues: String = "reject_without_mutating",
    val unsupportedNewerSchemas: String = "reject_without_mutating",
)

@Serializable
internal data class BackupLogicalSelection(
    val archiveId: String,
    val rideArchiveIds: List<Long>,
)

@Serializable
internal data class BackupMergeGroup(
    val archiveId: Long,
    val rideArchiveIdsInStartOrder: List<Long>,
)

@Serializable
internal data class BackupRide(
    val archiveId: Long,
    val vehicleArchiveId: Long? = null,
    val mergeGroupArchiveId: Long? = null,
    val startedAtEpochMs: Long,
    val startedElapsedNanos: Long,
    val endedAtEpochMs: Long,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val distanceMeters: Double? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double,
    val startAddress: String? = null,
    val endAddress: String? = null,
    val startSavedAddressArchiveId: Long? = null,
    val endSavedAddressArchiveId: Long? = null,
    /** Null only for archives produced before stable ride identity was introduced. */
    val rideUuid: String? = null,
)

@Serializable
internal data class BackupVehicle(
    val archiveId: Long,
    val name: String,
    val make: String,
    val model: String,
    val licensePlate: String,
    val fuelType: String,
    val mileageKm: Int,
    val isPrimary: Boolean,
    val bluetoothDevices: List<BackupBluetoothDevice>,
    val year: Int? = null,
    val fuelEconomy: Double? = null,
    val tankSize: Double? = null,
    val vehicleType: String? = null,
    val engine: String? = null,
    val manufacturingCountry: String? = null,
    /** Null only for archives produced before stable cross-backup vehicle identity existed. */
    val vehicleUuid: String? = null,
    /** Null only for legacy archives; their manifest creation time is the freshness fallback. */
    val updatedAtEpochMs: Long? = null,
)

@Serializable
internal data class BackupBluetoothDevice(
    val address: String,
    val name: String,
)

@Serializable
internal data class BackupSavedAddress(
    val archiveId: Long,
    val label: String,
    val kind: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val icon: String,
    val address: String? = null,
)

@Serializable
internal data class BackupRideEvent(
    val archiveId: Long,
    val rideArchiveId: Long,
    val type: String,
    val startOffsetMs: Long,
    val durationMs: Long,
    val peakG: Double,
    val peakJerkGPerS: Double,
    val avgG: Double,
    val speedMps: Double,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
internal data class BackupAnalysisState(
    val rideArchiveId: Long,
    val stage: String,
    val version: Int,
)

@Serializable
internal data class BackupRefuel(
    val archiveId: Long,
    val vehicleArchiveId: Long,
    val timestampEpochMs: Long,
    val fuelAmountMilliliters: Long,
    val totalPriceMinor: Long,
    val currencyCode: String,
    val odometerMeters: Long,
    val isFullTank: Boolean,
    val journeyAnchorRideArchiveId: Long? = null,
)

@Serializable
internal data class BackupFile(
    val rideArchiveId: Long,
    val role: String,
    val requirement: String,
    val status: String,
    val path: String,
    val mediaType: String,
    val contentEncoding: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

internal class RideBackupValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal fun encodeRideBackupManifest(manifest: RideBackupManifest): String = backupJson.encodeToString(manifest)

internal fun decodeRideBackupManifest(json: String): RideBackupManifest {
    val element =
        runCatching {
            backupJson.parseToJsonElement(json)
        }.getOrElse { throw RideBackupValidationException("Manifest is not valid JSON", it) }
    val schema =
        element.jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: throw RideBackupValidationException("Manifest has no integer schemaVersion")
    when {
        schema > RIDE_BACKUP_SCHEMA_VERSION -> throw RideBackupValidationException("Unsupported newer backup schema $schema")

        schema < RIDE_BACKUP_SCHEMA_VERSION -> throw RideBackupValidationException(
            "Backup schema $schema has no registered upgrade to $RIDE_BACKUP_SCHEMA_VERSION",
        )
    }
    return runCatching { backupJson.decodeFromJsonElement<RideBackupManifest>(element) }.getOrElse {
        throw RideBackupValidationException("Manifest does not conform to schema $schema", it)
    }
}

internal fun rawArchivePath(rideId: Long) = "data/rides/$rideId/samples.ndjson.gz"

internal fun routeArchivePath(
    rideId: Long,
    routeVersion: Int = ROUTE_VERSION,
) = "data/rides/$rideId/route.v$routeVersion"
