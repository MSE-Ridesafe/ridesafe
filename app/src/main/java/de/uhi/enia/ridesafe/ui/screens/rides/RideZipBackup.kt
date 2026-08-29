package de.uhi.enia.ridesafe.ui.screens.rides

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.withTransaction
import com.google.maps.android.PolyUtil
import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.RIDESAFE_DATABASE_VERSION
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideAnalysisState
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RideEventType
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.rides.RideDataCoordinator
import de.uhi.enia.ridesafe.rides.processing.AXIS_VERSION
import de.uhi.enia.ridesafe.rides.processing.ENDPOINTS_VERSION
import de.uhi.enia.ridesafe.rides.processing.EVENTS_VERSION
import de.uhi.enia.ridesafe.rides.processing.ROUTE_VERSION
import de.uhi.enia.ridesafe.rides.processing.processedRouteFile
import de.uhi.enia.ridesafe.rides.recording.RideSample
import de.uhi.enia.ridesafe.rides.recording.ridesDir
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal const val RIDE_BACKUP_FORMAT_ID = "de.uhi.enia.ridesafe.selected-rides"
internal const val RIDE_BACKUP_FORMAT_VERSION = 1
internal const val RIDE_BACKUP_SCHEMA_VERSION = 2
private const val MANIFEST_ENTRY = "manifest.json"
private const val RAW_ROLE = "raw_samples"
private const val ROUTE_ROLE = "processed_route"
private const val REQUIRED_SOURCE = "required_source"
private const val OPTIONAL_DERIVED = "optional_regenerable_derived"
private const val INCLUDED = "included"
private const val ABSENT = "absent"

private val backupJson =
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

internal data class RideBackupSourceFile(
    val metadata: BackupFile,
    val snapshot: File,
    val crc32: Long,
)

internal class RideBackupValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal class RideZipBackup(
    private val app: Application,
    private val db: RidesafeDatabase,
) {
    suspend fun write(
        destination: File,
        requests: List<RideExportRequest>,
    ) {
        val rideIds = requests.flatMap(RideExportRequest::rideIds).distinct()
        require(rideIds.isNotEmpty())
        val snapshotDirectory = Files.createTempDirectory(app.cacheDir.toPath(), "ridesafe_backup_snapshot_").toFile()
        try {
            val archive =
                RideDataCoordinator.withRides(rideIds) {
                    val databaseSnapshot = readDatabaseSnapshot(rideIds)
                    val sources = snapshotFiles(databaseSnapshot.rides, snapshotDirectory)
                    val manifest = databaseSnapshot.toManifest(app, requests, sources.map { it.metadata })
                    RideBackupArchiveValidator.validateManifest(manifest)
                    RideBackupArchive(manifest, sources)
                }
            currentCoroutineContext().ensureActive()
            writeRideBackupZip(destination, archive.manifest, archive.sources)
            currentCoroutineContext().ensureActive()
            RideBackupArchiveValidator.validate(destination)
        } finally {
            snapshotDirectory.deleteRecursively()
        }
    }

    private suspend fun readDatabaseSnapshot(rideIds: List<Long>): RideBackupSnapshot =
        db.withTransaction {
            val rides = db.rideDao().byIds(rideIds).sortedBy(Ride::startedAtEpochMs)
            requireFinishedSelectedRides(rideIds, rides)
            val groupIds = rides.mapNotNull(Ride::mergeGroupId).distinct()
            if (groupIds.isNotEmpty()) {
                val allGroupRideIds =
                    db
                        .rideDao()
                        .membersOfGroups(groupIds)
                        .map(Ride::id)
                        .toSet()
                require(rideIds.toSet().containsAll(allGroupRideIds)) { "A selected merge group is incomplete" }
            }
            val refuels = db.refuelDao().forJourneyAnchors(rideIds)
            val vehicleIds = (rides.mapNotNull(Ride::vehicleId) + refuels.map(Refuel::vehicleId)).distinct()
            val addressIds = rides.flatMap { listOfNotNull(it.startAddressId, it.endAddressId) }.distinct()
            RideBackupSnapshot(
                rides,
                if (vehicleIds.isEmpty()) emptyList() else db.vehicleDao().byIds(vehicleIds),
                if (addressIds.isEmpty()) emptyList() else db.savedAddressDao().byIds(addressIds),
                db.rideEventDao().eventsForRides(rideIds),
                db.rideAnalysisDao().forRides(rideIds),
                refuels,
            )
        }

    private suspend fun snapshotFiles(
        rides: List<Ride>,
        directory: File,
    ): List<RideBackupSourceFile> {
        val sources = mutableListOf<RideBackupSourceFile>()
        for (ride in rides) {
            currentCoroutineContext().ensureActive()
            val rawSource = File(ridesDir(app), ride.sampleFile)
            if (!rawSource.isFile) throw RideBackupValidationException("Required raw samples are missing for ride ${ride.id}")
            sources += snapshotIncludedFile(ride.id, RAW_ROLE, rawSource, directory)
            val routeSource = processedRouteFile(app, ride)
            sources +=
                if (routeSource.isFile) {
                    snapshotIncludedFile(ride.id, ROUTE_ROLE, routeSource, directory)
                } else {
                    RideBackupSourceFile(
                        routeFileMetadata(ride.id, ABSENT, null, null),
                        File(directory, "absent-${ride.id}.route.v$ROUTE_VERSION"),
                        0,
                    )
                }
        }
        return sources
    }

    private suspend fun snapshotIncludedFile(
        rideId: Long,
        role: String,
        source: File,
        directory: File,
    ): RideBackupSourceFile {
        val target = File(directory, "${rideId}_$role")
        copyCancellable(source, target)
        when (role) {
            RAW_ROLE -> validateRawSamples(target.inputStream())
            ROUTE_ROLE -> validateEncodedRoute(target.inputStream())
        }
        val integrity = fileIntegrity(target)
        val metadata =
            if (role == RAW_ROLE) {
                rawFileMetadata(rideId, integrity.size, integrity.sha256)
            } else {
                routeFileMetadata(rideId, INCLUDED, integrity.size, integrity.sha256)
            }
        return RideBackupSourceFile(metadata, target, integrity.crc32)
    }
}

internal fun requireFinishedSelectedRides(
    expectedRideIds: List<Long>,
    rides: List<Ride>,
) {
    require(rides.size == expectedRideIds.distinct().size && rides.map(Ride::id).toSet() == expectedRideIds.toSet()) {
        "Selected rides no longer exist"
    }
    require(rides.all { it.endedAtEpochMs != null }) { "Active rides cannot be exported" }
}

private data class RideBackupArchive(
    val manifest: RideBackupManifest,
    val sources: List<RideBackupSourceFile>,
)

private data class RideBackupSnapshot(
    val rides: List<Ride>,
    val vehicles: List<Vehicle>,
    val savedAddresses: List<SavedAddress>,
    val rideEvents: List<RideEvent>,
    val analysisStates: List<RideAnalysisState>,
    val refuels: List<Refuel>,
)

private fun RideBackupSnapshot.toManifest(
    app: Application,
    requests: List<RideExportRequest>,
    files: List<BackupFile>,
): RideBackupManifest {
    val groupIds = rides.mapNotNull(Ride::mergeGroupId).distinct().sorted()
    return RideBackupManifest(
        createdAtEpochMs = System.currentTimeMillis(),
        producer = producer(app),
        sourceDatabaseVersion = RIDESAFE_DATABASE_VERSION,
        processingVersions = BackupProcessingVersions(ROUTE_VERSION, AXIS_VERSION, EVENTS_VERSION, ENDPOINTS_VERSION),
        logicalSelections =
            requests.mapIndexed {
                index,
                request,
                ->
                BackupLogicalSelection("selection-${index + 1}", request.rideIds.distinct())
            },
        mergeGroups =
            groupIds.map { id ->
                BackupMergeGroup(id, rides.filter { it.mergeGroupId == id }.sortedBy(Ride::startedAtEpochMs).map(Ride::id))
            },
        rides = rides.map(Ride::toBackup),
        vehicles = vehicles.sortedBy(Vehicle::id).map(Vehicle::toBackup),
        savedAddresses = savedAddresses.sortedBy(SavedAddress::id).map(SavedAddress::toBackup),
        rideEvents = rideEvents.map(RideEvent::toBackup),
        analysisStates = analysisStates.map(RideAnalysisState::toBackup),
        refuels = refuels.map(Refuel::toBackup),
        files = files.sortedWith(compareBy(BackupFile::rideArchiveId, BackupFile::role)),
    )
}

private fun producer(app: Application): BackupProducer {
    val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
    return BackupProducer(
        app.packageName,
        info.versionName.orEmpty(),
        info.longVersionCode,
        "android",
        Build.VERSION.RELEASE,
        Build.VERSION.SDK_INT,
    )
}

internal suspend fun writeRideBackupZip(
    destination: File,
    manifest: RideBackupManifest,
    sources: List<RideBackupSourceFile>,
) {
    currentCoroutineContext().ensureActive()
    RideBackupArchiveValidator.validateManifest(manifest)
    val included = sources.filter { it.metadata.status == INCLUDED }.associateBy { it.metadata.path }
    require(included.size == sources.count { it.metadata.status == INCLUDED }) { "Duplicate source paths" }
    require(
        included.keys ==
            manifest.files
                .filter {
                    it.status == INCLUDED
                }.map(BackupFile::path)
                .toSet(),
    ) { "ZIP sources do not match the manifest" }
    ZipOutputStream(BufferedOutputStream(destination.outputStream())).use { zip ->
        putBytes(zip, MANIFEST_ENTRY, encodeRideBackupManifest(manifest).toByteArray(Charsets.UTF_8))
        for (file in manifest.files.filter { it.status == INCLUDED }.sortedBy(BackupFile::path)) {
            currentCoroutineContext().ensureActive()
            val source = included.getValue(file.path)
            val entry = ZipEntry(file.path).apply { time = 0L }
            if (file.contentEncoding == "gzip") {
                entry.method = ZipEntry.STORED
                entry.size = file.sizeBytes!!
                entry.compressedSize = file.sizeBytes
                entry.crc = source.crc32
            }
            zip.putNextEntry(entry)
            source.snapshot
                .inputStream()
                .buffered()
                .use { input -> copyCancellable(input, zip) }
            zip.closeEntry()
        }
    }
}

private fun putBytes(
    zip: ZipOutputStream,
    path: String,
    bytes: ByteArray,
) {
    zip.putNextEntry(ZipEntry(path).apply { time = 0L })
    zip.write(bytes)
    zip.closeEntry()
}

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

/** Reference reader used after every export and by tests as the contract a future importer follows. */
internal object RideBackupArchiveValidator {
    fun validate(archive: File): RideBackupManifest {
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val names = entries.map(ZipEntry::getName)
                if (names.size != names.toSet().size) fail("ZIP contains duplicate paths")
                names.forEach(::validateArchivePath)
                val manifestEntry = zip.getEntry(MANIFEST_ENTRY) ?: fail("ZIP has no $MANIFEST_ENTRY")
                val manifest =
                    decodeRideBackupManifest(zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() })
                validateManifest(manifest)
                val included = manifest.files.filter { it.status == INCLUDED }
                val expectedPaths = setOf(MANIFEST_ENTRY) + included.map(BackupFile::path)
                if (names.toSet() != expectedPaths) fail("ZIP entries do not exactly match the manifest")
                for (descriptor in included) {
                    val entry = zip.getEntry(descriptor.path) ?: fail("Missing ZIP entry ${descriptor.path}")
                    if (descriptor.contentEncoding == "gzip" &&
                        entry.method != ZipEntry.STORED
                    ) {
                        fail("Gzip entry ${descriptor.path} must use ZIP STORED mode")
                    }
                    val integrity = zip.getInputStream(entry).use(::streamIntegrity)
                    if (integrity.size != descriptor.sizeBytes) fail("Byte-size mismatch for ${descriptor.path}")
                    if (!integrity.sha256.equals(descriptor.sha256, ignoreCase = true)) fail("SHA-256 mismatch for ${descriptor.path}")
                    when (descriptor.role) {
                        RAW_ROLE -> zip.getInputStream(entry).use(::validateRawSamples)
                        ROUTE_ROLE -> zip.getInputStream(entry).use(::validateEncodedRoute)
                    }
                }
                return manifest
            }
        } catch (failure: RideBackupValidationException) {
            throw failure
        } catch (failure: Exception) {
            throw RideBackupValidationException("Backup ZIP is corrupt or unreadable", failure)
        }
    }

    fun validateManifest(manifest: RideBackupManifest) {
        if (manifest.formatId != RIDE_BACKUP_FORMAT_ID ||
            manifest.formatVersion != RIDE_BACKUP_FORMAT_VERSION
        ) {
            fail("Unsupported backup format")
        }
        if (manifest.schemaVersion != RIDE_BACKUP_SCHEMA_VERSION) fail("Unsupported backup schema ${manifest.schemaVersion}")
        if (
            !manifest.contract.numericIdsAreArchiveLocal ||
            !manifest.contract.importerMustRemapIds ||
            manifest.contract.rawNdjsonRecordsGloballyTimestampOrdered ||
            manifest.contract.unknownFields != "ignore" ||
            manifest.contract.unknownEnumValues != "reject_without_mutating" ||
            manifest.contract.unsupportedNewerSchemas != "reject_without_mutating"
        ) {
            fail("Archive compatibility contract is missing or unsupported")
        }
        unique(manifest.rides.map(BackupRide::archiveId), "ride IDs")
        manifest.rides.forEach { ride ->
            ride.rideUuid?.let { uuid ->
                if (runCatching { java.util.UUID.fromString(uuid) }.isFailure) fail("Invalid ride UUID")
            }
        }
        unique(manifest.rides.mapNotNull(BackupRide::rideUuid).map(String::lowercase), "ride UUIDs")
        unique(manifest.vehicles.map(BackupVehicle::archiveId), "vehicle IDs")
        unique(manifest.savedAddresses.map(BackupSavedAddress::archiveId), "saved-address IDs")
        unique(manifest.rideEvents.map(BackupRideEvent::archiveId), "event IDs")
        unique(manifest.refuels.map(BackupRefuel::archiveId), "refuel IDs")
        unique(manifest.mergeGroups.map(BackupMergeGroup::archiveId), "merge-group IDs")
        unique(manifest.logicalSelections.map(BackupLogicalSelection::archiveId), "selection IDs")
        unique(manifest.analysisStates.map { it.rideArchiveId to it.stage }, "analysis-state keys")

        val rides = manifest.rides.map(BackupRide::archiveId).toSet()
        val vehicles = manifest.vehicles.map(BackupVehicle::archiveId).toSet()
        val addresses = manifest.savedAddresses.map(BackupSavedAddress::archiveId).toSet()
        val groups = manifest.mergeGroups.associateBy(BackupMergeGroup::archiveId)
        if (rides.isEmpty()) fail("Backup contains no rides")
        manifest.rides.forEach { ride ->
            requireRef(ride.vehicleArchiveId, vehicles, true, "ride vehicle")
            requireRef(ride.startSavedAddressArchiveId, addresses, true, "ride start address")
            requireRef(ride.endSavedAddressArchiveId, addresses, true, "ride end address")
            requireRef(ride.mergeGroupArchiveId, groups.keys, true, "ride merge group")
        }
        manifest.mergeGroups.forEach { group ->
            if (group.rideArchiveIdsInStartOrder.isEmpty()) fail("Merge group ${group.archiveId} is empty")
            unique(group.rideArchiveIdsInStartOrder, "rides in merge group ${group.archiveId}")
            group.rideArchiveIdsInStartOrder.forEach { requireRef(it, rides, false, "merge-group ride") }
            val reverse =
                manifest.rides
                    .filter { it.mergeGroupArchiveId == group.archiveId }
                    .map(BackupRide::archiveId)
                    .toSet()
            if (reverse != group.rideArchiveIdsInStartOrder.toSet()) fail("Merge group ${group.archiveId} is not bidirectional")
        }
        val selected = mutableListOf<Long>()
        manifest.logicalSelections.forEach { selection ->
            if (selection.rideArchiveIds.isEmpty()) fail("Logical selection ${selection.archiveId} is empty")
            unique(selection.rideArchiveIds, "rides in ${selection.archiveId}")
            selection.rideArchiveIds.forEach { requireRef(it, rides, false, "selection ride") }
            selected += selection.rideArchiveIds
        }
        if (selected.size != selected.toSet().size || selected.toSet() != rides) fail("Logical selections must partition all rides")
        manifest.rideEvents.forEach {
            requireRef(it.rideArchiveId, rides, false, "event ride")
            if (it.type !in RideEventType.entries.map { value -> value.name }) fail("Unknown ride-event enum ${it.type}")
        }
        manifest.analysisStates.forEach { requireRef(it.rideArchiveId, rides, false, "analysis ride") }
        manifest.refuels.forEach {
            requireRef(it.vehicleArchiveId, vehicles, false, "refuel vehicle")
            requireRef(it.journeyAnchorRideArchiveId, rides, true, "refuel journey anchor")
        }
        manifest.vehicles.forEach {
            if (it.fuelType !in FuelType.entries.map { value -> value.name }) fail("Unknown fuel-type enum ${it.fuelType}")
            it.vehicleUuid?.let { uuid ->
                if (runCatching { java.util.UUID.fromString(uuid) }.isFailure) fail("Invalid vehicle UUID")
            }
            if (it.updatedAtEpochMs != null && it.updatedAtEpochMs < 0) fail("Invalid vehicle modification time")
        }
        unique(manifest.vehicles.mapNotNull(BackupVehicle::vehicleUuid), "vehicle UUIDs")
        manifest.savedAddresses.forEach {
            if (it.kind !in
                SavedPlaceKind.entries.map { value -> value.name }
            ) {
                fail("Unknown saved-place enum ${it.kind}")
            }
        }

        unique(manifest.files.map(BackupFile::path), "manifest file paths")
        manifest.files.forEach { descriptor ->
            validateArchivePath(descriptor.path)
            requireRef(descriptor.rideArchiveId, rides, false, "file ride")
            val expected =
                if (descriptor.role == RAW_ROLE) {
                    rawArchivePath(descriptor.rideArchiveId)
                } else {
                    routeArchivePath(descriptor.rideArchiveId, manifest.processingVersions.route)
                }
            if (descriptor.role !in setOf(RAW_ROLE, ROUTE_ROLE) ||
                descriptor.path != expected
            ) {
                fail("Noncanonical file path ${descriptor.path}")
            }
            when (descriptor.status) {
                INCLUDED -> {
                    if (descriptor.sizeBytes == null || descriptor.sizeBytes < 0 ||
                        !isSha256(descriptor.sha256)
                    ) {
                        fail("Included file ${descriptor.path} lacks integrity metadata")
                    }
                }

                ABSENT -> {
                    if (descriptor.requirement != OPTIONAL_DERIVED || descriptor.sizeBytes != null ||
                        descriptor.sha256 != null
                    ) {
                        fail("Only optional derived files may be absent")
                    }
                }

                else -> {
                    fail("Unknown file status ${descriptor.status}")
                }
            }
            if (descriptor.role == RAW_ROLE && descriptor.requirement != REQUIRED_SOURCE) fail("Raw samples must be required")
            if (descriptor.role == ROUTE_ROLE && descriptor.requirement != OPTIONAL_DERIVED) fail("Routes must be optional derived files")
        }
        manifest.rides.forEach { ride ->
            val descriptors = manifest.files.filter { it.rideArchiveId == ride.archiveId }
            if (descriptors.count { it.role == RAW_ROLE && it.status == INCLUDED } != 1 ||
                descriptors.count { it.role == ROUTE_ROLE } != 1
            ) {
                fail("Ride ${ride.archiveId} must have one included raw file and one route descriptor")
            }
        }
    }
}

private fun rawArchivePath(rideId: Long) = "data/rides/$rideId/samples.ndjson.gz"

private fun routeArchivePath(
    rideId: Long,
    routeVersion: Int = ROUTE_VERSION,
) = "data/rides/$rideId/route.v$routeVersion"

private fun rawFileMetadata(
    rideId: Long,
    size: Long,
    sha256: String,
) = BackupFile(rideId, RAW_ROLE, REQUIRED_SOURCE, INCLUDED, rawArchivePath(rideId), "application/x-ndjson", "gzip", size, sha256)

private fun routeFileMetadata(
    rideId: Long,
    status: String,
    size: Long?,
    sha256: String?,
) = BackupFile(
    rideId,
    ROUTE_ROLE,
    OPTIONAL_DERIVED,
    status,
    routeArchivePath(rideId),
    "application/vnd.google.polyline",
    "google-encoded-polyline-1e5",
    size,
    sha256,
)

private data class FileIntegrity(
    val size: Long,
    val sha256: String,
    val crc32: Long,
)

private fun fileIntegrity(file: File): FileIntegrity = file.inputStream().use(::streamIntegrity)

private fun streamIntegrity(input: InputStream): FileIntegrity {
    val digest = MessageDigest.getInstance("SHA-256")
    val crc = CRC32()
    var size = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
        crc.update(buffer, 0, count)
        size += count
    }
    return FileIntegrity(size, digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }, crc.value)
}

private suspend fun copyCancellable(
    source: File,
    destination: File,
) {
    source.inputStream().buffered().use { input -> destination.outputStream().buffered().use { output -> copyCancellable(input, output) } }
}

private suspend fun copyCancellable(
    input: InputStream,
    output: java.io.OutputStream,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
    }
}

private fun validateRawSamples(input: InputStream) {
    try {
        GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) fail("Raw sample record ${index + 1} is blank")
                runCatching { backupJson.decodeFromString<RideSample>(line) }.getOrElse {
                    throw RideBackupValidationException("Raw sample record ${index + 1} is invalid", it)
                }
            }
        }
    } catch (failure: RideBackupValidationException) {
        throw failure
    } catch (failure: Exception) {
        throw RideBackupValidationException("Raw sample gzip stream is corrupt", failure)
    }
}

private fun validateEncodedRoute(input: InputStream) {
    val encoded = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val points =
        runCatching {
            PolyUtil.decode(encoded)
        }.getOrElse { throw RideBackupValidationException("Processed route is not a valid encoded polyline", it) }
    if (points.any { it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 }) fail("Processed route contains an invalid coordinate")
}

private fun validateArchivePath(path: String) {
    if (path.isBlank() || path.startsWith('/') || '\\' in path || path.endsWith('/') ||
        path.split('/').any { it.isBlank() || it == "." || it == ".." }
    ) {
        fail("Unsafe archive path: $path")
    }
    if (!path.matches(Regex("[A-Za-z0-9._/-]+"))) fail("Archive path is not normalized: $path")
}

private fun isSha256(value: String?) = value?.matches(Regex("[0-9a-f]{64}")) == true

private fun <T> unique(
    values: List<T>,
    label: String,
) {
    if (values.size != values.toSet().size) fail("Duplicate $label")
}

private fun <T> requireRef(
    value: T?,
    targets: Set<T>,
    nullable: Boolean,
    label: String,
) {
    if (value == null) {
        if (!nullable) fail("$label is unexpectedly null")
    } else if (value !in targets) {
        fail("Broken $label reference: $value")
    }
}

private fun fail(message: String): Nothing = throw RideBackupValidationException(message)

private fun Ride.toBackup() =
    BackupRide(
        archiveId = id,
        vehicleArchiveId = vehicleId,
        mergeGroupArchiveId = mergeGroupId,
        startedAtEpochMs = startedAtEpochMs,
        startedElapsedNanos = startedElapsedNanos,
        endedAtEpochMs = requireNotNull(endedAtEpochMs),
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        distanceMeters = distanceMeters,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        startAddress = startAddress,
        endAddress = endAddress,
        startSavedAddressArchiveId = startAddressId,
        endSavedAddressArchiveId = endAddressId,
        rideUuid = rideUuid,
    )

private fun Vehicle.toBackup() =
    BackupVehicle(
        archiveId = id,
        name = name,
        make = make,
        model = model,
        licensePlate = licensePlate,
        fuelType = fuelType.name,
        mileageKm = mileageKm,
        isPrimary = isPrimary,
        bluetoothDevices = bluetoothDevices.map { BackupBluetoothDevice(it.address, it.name) },
        year = year,
        fuelEconomy = fuelEconomy,
        tankSize = tankSize,
        vehicleType = vehicleType,
        engine = engine,
        manufacturingCountry = manufacturingCountry,
        vehicleUuid = vehicleUuid,
        updatedAtEpochMs = updatedAtEpochMs,
    )

private fun SavedAddress.toBackup() = BackupSavedAddress(id, label, kind.name, latitude, longitude, radiusMeters, icon, address)

private fun RideEvent.toBackup() =
    BackupRideEvent(id, rideId, type.name, startOffsetMs, durationMs, peakG, peakJerkGPerS, avgG, speedMps, lat, lon)

private fun RideAnalysisState.toBackup() = BackupAnalysisState(rideId, stage, version)

private fun Refuel.toBackup() =
    BackupRefuel(
        id,
        vehicleId,
        timestampEpochMs,
        fuelAmountMilliliters,
        totalPriceMinor,
        currencyCode,
        odometerMeters,
        isFullTank,
        journeyAnchorRideId,
    )
