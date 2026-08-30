package de.uhi.enia.ridesafe.backup

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.withTransaction
import de.uhi.enia.ridesafe.data.RIDESAFE_DATABASE_VERSION
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideAnalysisState
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.export.RideExportProgress
import de.uhi.enia.ridesafe.export.RideExportRequest
import de.uhi.enia.ridesafe.rides.RideDataCoordinator
import de.uhi.enia.ridesafe.rides.processing.AXIS_VERSION
import de.uhi.enia.ridesafe.rides.processing.ENDPOINTS_VERSION
import de.uhi.enia.ridesafe.rides.processing.EVENTS_VERSION
import de.uhi.enia.ridesafe.rides.processing.ROUTE_VERSION
import de.uhi.enia.ridesafe.rides.processing.processedRouteFile
import de.uhi.enia.ridesafe.rides.recording.ridesDir
import de.uhi.enia.ridesafe.util.copyCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class RideBackupSourceFile(
    val metadata: BackupFile,
    val file: File,
    val crc32: Long,
)

internal class RideZipBackup(
    private val app: Application,
    private val db: RidesafeDatabase,
) {
    /**
     * The ZIP streams each ride's own file rather than a private copy of it, so the per-ride locks
     * are held across the archive write instead of only across a copy pass. That is strictly less
     * time than before — copying every selected file took about as long as writing the archive
     * does, and it needed a second copy of the whole selection in the cache while it did.
     */
    suspend fun write(
        destination: File,
        requests: List<RideExportRequest>,
        onProgress: (RideExportProgress) -> Unit = {},
    ) {
        val rideIds = requests.flatMap(RideExportRequest::rideIds).distinct()
        require(rideIds.isNotEmpty())
        var passes = 0
        val report = { rides: Int -> onProgress(RideExportProgress(++passes, rides)) }
        RideDataCoordinator.withRides(rideIds) {
            val databaseSnapshot = readDatabaseSnapshot(rideIds)
            val rides = databaseSnapshot.rides.size
            onProgress(RideExportProgress(0, rides))
            val sources = describeFiles(databaseSnapshot.rides) { report(rides) }
            val manifest = databaseSnapshot.toManifest(app, requests, sources.map { it.metadata })
            RideBackupArchiveValidator.validateManifest(manifest)
            writeRideBackupZip(destination, manifest, sources) { report(rides) }
            // Cancellation is checked per archive entry from here on: the reader is not a suspend
            // function, so it borrows this coroutine's context rather than the caller's.
            val context = currentCoroutineContext()
            RideBackupArchiveValidator.validate(destination) {
                context.ensureActive()
                report(rides)
            }
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

    private suspend fun describeFiles(
        rides: List<Ride>,
        onRide: () -> Unit,
    ): List<RideBackupSourceFile> {
        val sources = mutableListOf<RideBackupSourceFile>()
        for (ride in rides) {
            currentCoroutineContext().ensureActive()
            val rawSource = File(ridesDir(app), ride.sampleFile)
            if (!rawSource.isFile) throw RideBackupValidationException("Required raw samples are missing for ride ${ride.id}")
            sources += describeIncludedFile(ride.id, RAW_ROLE, rawSource)
            val routeSource = processedRouteFile(app, ride)
            sources +=
                if (routeSource.isFile) {
                    describeIncludedFile(ride.id, ROUTE_ROLE, routeSource)
                } else {
                    // Never opened: an absent descriptor is filtered out before any entry is written.
                    RideBackupSourceFile(routeFileMetadata(ride.id, ABSENT, null, null), routeSource, 0)
                }
            onRide()
        }
        return sources
    }

    /** Checks the file's framing first so a corrupt ride fails before the archive is written. */
    private fun describeIncludedFile(
        rideId: Long,
        role: String,
        source: File,
    ): RideBackupSourceFile {
        when (role) {
            RAW_ROLE -> source.inputStream().use { validateRawSamples(it) }
            ROUTE_ROLE -> source.inputStream().use(::validateEncodedRoute)
        }
        val integrity = fileIntegrity(source)
        val metadata =
            if (role == RAW_ROLE) {
                rawFileMetadata(rideId, integrity.size, integrity.sha256)
            } else {
                routeFileMetadata(rideId, INCLUDED, integrity.size, integrity.sha256)
            }
        return RideBackupSourceFile(metadata, source, integrity.crc32)
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
    onRide: () -> Unit = {},
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
            source.file
                .inputStream()
                .buffered()
                .use { input -> copyCancellable(input, zip) }
            zip.closeEntry()
            if (file.role == RAW_ROLE) onRide()
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
