package de.uhi.enia.ridesafe.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.room.withTransaction
import de.uhi.enia.ridesafe.data.BtDevice
import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideAnalysisState
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RideEventType
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.rides.processing.ROUTE_VERSION
import de.uhi.enia.ridesafe.rides.processing.processedRouteFile
import de.uhi.enia.ridesafe.rides.recording.ridesDir
import de.uhi.enia.ridesafe.ui.screens.rides.BackupFile
import de.uhi.enia.ridesafe.ui.screens.rides.BackupRide
import de.uhi.enia.ridesafe.ui.screens.rides.BackupVehicle
import de.uhi.enia.ridesafe.ui.screens.rides.RideBackupArchiveValidator
import de.uhi.enia.ridesafe.ui.screens.rides.RideBackupManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class RideBackupImportPreview(
    val createdAtEpochMs: Long,
    val rides: Int,
    val vehicles: Int,
    val savedAddresses: Int,
    val refuels: Int,
)

data class RideBackupImportResult(
    val rides: Int,
    val vehicles: Int,
    val savedAddresses: Int,
    val refuels: Int,
)

internal fun interface ImportFilePublisher {
    suspend fun publish(source: File, destination: File)
}

private val atomicImportFilePublisher =
    ImportFilePublisher { source, destination ->
        destination.parentFile?.mkdirs()
        require(!destination.exists()) { "Import destination already exists: ${destination.name}" }
        val temporary = File(destination.parentFile, ".ridesafe_import_${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                source.inputStream().buffered().use { input -> copyCancellable(input, output) }
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

internal class RideBackupImporter(
    private val app: Application,
    private val db: RidesafeDatabase = RidesafeDatabase.getInstance(app),
    private val filePublisher: ImportFilePublisher = atomicImportFilePublisher,
) {
    suspend fun inspect(uri: Uri): RideBackupImportPreview =
        withLocalArchive(uri) { archive ->
            val manifest = RideBackupArchiveValidator.validate(archive)
            manifest.preview()
        }

    suspend fun import(uri: Uri): RideBackupImportResult =
        withLocalArchive(uri) { archive -> importArchive(archive) }

    internal suspend fun importArchive(archive: File): RideBackupImportResult =
        withContext(Dispatchers.IO) {
            val manifest = RideBackupArchiveValidator.validate(archive)
            val staging = Files.createTempDirectory(app.cacheDir.toPath(), "ridesafe_import_").toFile()
            val published = mutableListOf<File>()
            try {
                val stagedFiles = extractIncludedFiles(archive, manifest, staging)
                val token = UUID.randomUUID().toString().replace("-", "")
                val sampleNames = manifest.rides.associate { it.archiveId to "ride_import_${token}_${it.archiveId}.ndjson.gz" }
                val importedVehicleCount = db.withTransaction {
                    val vehicleIds = insertVehicles(manifest)
                    val addressIds = insertAddresses(manifest)
                    val rideIds = insertRides(manifest, vehicleIds, addressIds, sampleNames)
                    restoreMergeGroups(manifest, rideIds)
                    insertEvents(manifest, rideIds)
                    insertAnalysisStates(manifest, rideIds)
                    insertRefuels(manifest, vehicleIds, rideIds)
                    publishFiles(manifest, stagedFiles, rideIds, sampleNames, published)
                    vehicleIds.values.toSet().size
                }
                RideBackupImportResult(
                    manifest.rides.size,
                    importedVehicleCount,
                    manifest.savedAddresses.size,
                    manifest.refuels.size,
                )
            } catch (failure: Exception) {
                published.forEach { it.delete() }
                throw failure
            } finally {
                staging.deleteRecursively()
            }
        }

    private suspend fun insertVehicles(manifest: RideBackupManifest): Map<Long, Long> {
        val dao = db.vehicleDao()
        val existing = dao.all().toMutableList()
        val primaryArchiveId =
            if (existing.isEmpty()) {
                manifest.vehicles.firstOrNull { it.isPrimary }?.archiveId ?: manifest.vehicles.firstOrNull()?.archiveId
            } else {
                null
            }

        // Put the archived primary first when the garage is empty. This preserves the one-primary
        // invariant even if multiple legacy archive records collapse onto the same physical car.
        val ordered =
            manifest.vehicles.sortedWith(
                compareBy<BackupVehicle>({ it.archiveId != primaryArchiveId }, { vehicleFreshness(it, manifest) }, { it.archiveId }),
            )
        val mappings = mutableMapOf<Long, Long>()
        ordered.forEach { archived ->
            val matched = findMatchingVehicle(archived, existing)
            if (matched == null) {
                val inserted = archived.toVehicle(archived.archiveId == primaryArchiveId, manifest)
                val id = dao.insert(inserted)
                existing += inserted.copy(id = id)
                mappings[archived.archiveId] = id
            } else {
                val resolved = resolveVehicleConflict(matched, archived, manifest)
                if (resolved != matched) {
                    dao.update(resolved)
                    existing[existing.indexOfFirst { it.id == matched.id }] = resolved
                }
                mappings[archived.archiveId] = matched.id
            }
        }
        return mappings
    }

    private suspend fun insertAddresses(manifest: RideBackupManifest): Map<Long, Long> =
        manifest.savedAddresses.associate { archived ->
            archived.archiveId to
                db.savedAddressDao().insert(
                    SavedAddress(
                        label = archived.label,
                        kind = SavedPlaceKind.valueOf(archived.kind),
                        latitude = archived.latitude,
                        longitude = archived.longitude,
                        radiusMeters = archived.radiusMeters,
                        icon = archived.icon,
                        address = archived.address,
                    ),
                )
        }

    private suspend fun insertRides(
        manifest: RideBackupManifest,
        vehicleIds: Map<Long, Long>,
        addressIds: Map<Long, Long>,
        sampleNames: Map<Long, String>,
    ): Map<Long, Long> =
        manifest.rides.associate { archived ->
            archived.archiveId to
                db.rideDao().insert(
                    archived.toEntity(
                        vehicleId = archived.vehicleArchiveId?.let(vehicleIds::getValue),
                        startAddressId = archived.startSavedAddressArchiveId?.let(addressIds::getValue),
                        endAddressId = archived.endSavedAddressArchiveId?.let(addressIds::getValue),
                        sampleFile = sampleNames.getValue(archived.archiveId),
                    ),
                )
        }

    private suspend fun restoreMergeGroups(manifest: RideBackupManifest, rideIds: Map<Long, Long>) {
        manifest.mergeGroups.forEach { group ->
            val members = group.rideArchiveIdsInStartOrder.map(rideIds::getValue)
            db.rideDao().setMergeGroup(members.min(), members)
        }
    }

    private suspend fun insertEvents(manifest: RideBackupManifest, rideIds: Map<Long, Long>) {
        if (manifest.rideEvents.isEmpty()) return
        db.rideEventDao().insertAll(
            manifest.rideEvents.map { event ->
                RideEvent(
                    rideId = rideIds.getValue(event.rideArchiveId),
                    type = RideEventType.valueOf(event.type),
                    startOffsetMs = event.startOffsetMs,
                    durationMs = event.durationMs,
                    peakG = event.peakG,
                    peakJerkGPerS = event.peakJerkGPerS,
                    avgG = event.avgG,
                    speedMps = event.speedMps,
                    lat = event.lat,
                    lon = event.lon,
                )
            },
        )
    }

    private suspend fun insertAnalysisStates(manifest: RideBackupManifest, rideIds: Map<Long, Long>) {
        val routesAvailable =
            manifest.files
                .filter { it.role == "processed_route" && it.status == "included" }
                .map(BackupFile::rideArchiveId)
                .toSet()
        manifest.analysisStates
            .filterNot {
                it.stage == "route" &&
                    (manifest.processingVersions.route != ROUTE_VERSION || it.rideArchiveId !in routesAvailable)
            }
            .forEach { state ->
                db.rideAnalysisDao().stamp(RideAnalysisState(rideIds.getValue(state.rideArchiveId), state.stage, state.version))
            }
    }

    private suspend fun insertRefuels(
        manifest: RideBackupManifest,
        vehicleIds: Map<Long, Long>,
        rideIds: Map<Long, Long>,
    ) {
        manifest.refuels.forEach { refuel ->
            db.refuelDao().insert(
                Refuel(
                    vehicleId = vehicleIds.getValue(refuel.vehicleArchiveId),
                    timestampEpochMs = refuel.timestampEpochMs,
                    fuelAmountMilliliters = refuel.fuelAmountMilliliters,
                    totalPriceMinor = refuel.totalPriceMinor,
                    currencyCode = refuel.currencyCode,
                    odometerMeters = refuel.odometerMeters,
                    isFullTank = refuel.isFullTank,
                    journeyAnchorRideId = refuel.journeyAnchorRideArchiveId?.let(rideIds::getValue),
                ),
            )
        }
    }

    private suspend fun publishFiles(
        manifest: RideBackupManifest,
        staged: Map<String, File>,
        rideIds: Map<Long, Long>,
        sampleNames: Map<Long, String>,
        published: MutableList<File>,
    ) {
        val destinationDirectory = ridesDir(app).apply { mkdirs() }
        manifest.rides.forEach { ride ->
            coroutineContext.ensureActive()
            val raw = manifest.file(ride.archiveId, "raw_samples")
            publish(staged.getValue(raw.path), File(destinationDirectory, sampleNames.getValue(ride.archiveId)), published)
            val route = manifest.file(ride.archiveId, "processed_route")
            if (route.status == "included" && manifest.processingVersions.route == ROUTE_VERSION) {
                val restoredRide = ride.toEntity(null, null, null, sampleNames.getValue(ride.archiveId)).copy(id = rideIds.getValue(ride.archiveId))
                publish(staged.getValue(route.path), processedRouteFile(app, restoredRide), published)
            }
        }
    }

    private suspend fun publish(source: File, destination: File, published: MutableList<File>) {
        filePublisher.publish(source, destination)
        published += destination
    }

    private suspend fun extractIncludedFiles(
        archive: File,
        manifest: RideBackupManifest,
        staging: File,
    ): Map<String, File> =
        ZipFile(archive).use { zip ->
            manifest.files.filter { it.status == "included" }.mapIndexed { index, descriptor ->
                coroutineContext.ensureActive()
                val target = File(staging, "entry_$index")
                zip.getInputStream(zip.getEntry(descriptor.path)).use { input ->
                    target.outputStream().buffered().use { output -> copyCancellable(input, output) }
                }
                descriptor.path to target
            }.toMap()
        }

    private suspend fun <T> withLocalArchive(uri: Uri, operation: suspend (File) -> T): T =
        withContext(Dispatchers.IO) {
            val local = File.createTempFile("ridesafe_import_", ".zip", app.cacheDir)
            try {
                val input = app.contentResolver.openInputStream(uri) ?: error("The selected backup cannot be opened")
                input.use { source -> local.outputStream().buffered().use { target -> copyCancellable(source, target) } }
                operation(local)
            } finally {
                local.delete()
            }
        }
}

private suspend fun copyCancellable(input: java.io.InputStream, output: java.io.OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}

/**
 * Stable UUID is authoritative. Plate/Bluetooth matching exists only for legacy archives that did
 * not yet carry that identity. Ambiguous evidence deliberately creates a separate garage entry.
 */
internal fun findMatchingVehicle(
    archived: BackupVehicle,
    existing: List<Vehicle>,
): Vehicle? {
    archived.vehicleUuid?.let { uuid ->
        return existing.singleOrNull { it.vehicleUuid.equals(uuid, ignoreCase = true) }
    }

    val plate = normalizeLicensePlate(archived.licensePlate)
    val plateMatches =
        if (plate.isEmpty()) emptyList() else existing.filter { normalizeLicensePlate(it.licensePlate) == plate }
    val archivedBluetooth = archived.bluetoothDevices.mapNotNull { normalizeBluetoothAddress(it.address) }.toSet()
    val bluetoothMatches =
        if (archivedBluetooth.isEmpty()) {
            emptyList()
        } else {
            existing.filter { vehicle ->
                vehicle.bluetoothDevices.any { normalizeBluetoothAddress(it.address) in archivedBluetooth }
            }
        }

    return when {
        plateMatches.isNotEmpty() && bluetoothMatches.isNotEmpty() ->
            plateMatches.intersect(bluetoothMatches.toSet()).singleOrNull()
        plateMatches.size == 1 -> plateMatches.single()
        bluetoothMatches.size == 1 -> bluetoothMatches.single()
        else -> null
    }
}

internal fun normalizeLicensePlate(value: String): String =
    value.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)

private fun normalizeBluetoothAddress(value: String): String? =
    value.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).takeIf { it.length == 12 }

private fun vehicleFreshness(
    archived: BackupVehicle,
    manifest: RideBackupManifest,
): Long = archived.updatedAtEpochMs ?: manifest.createdAtEpochMs

private fun BackupVehicle.toVehicle(
    isPrimary: Boolean,
    manifest: RideBackupManifest,
): Vehicle =
    Vehicle(
        name = name,
        make = make,
        model = model,
        licensePlate = licensePlate,
        fuelType = FuelType.valueOf(fuelType),
        mileageKm = mileageKm,
        isPrimary = isPrimary,
        bluetoothDevices = bluetoothDevices.map { BtDevice(it.address, it.name) },
        year = year,
        fuelEconomy = fuelEconomy,
        tankSize = tankSize,
        vehicleUuid = vehicleUuid ?: UUID.randomUUID().toString(),
        updatedAtEpochMs = vehicleFreshness(this, manifest),
    )

private fun resolveVehicleConflict(
    local: Vehicle,
    archived: BackupVehicle,
    manifest: RideBackupManifest,
): Vehicle {
    val archivedFreshness = vehicleFreshness(archived, manifest)
    val archiveWins = archivedFreshness > local.updatedAtEpochMs
    val resolvedMileage =
        if (archived.updatedAtEpochMs == null) {
            maxOf(local.mileageKm, archived.mileageKm)
        } else if (archiveWins) {
            archived.mileageKm
        } else {
            local.mileageKm
        }

    if (!archiveWins) {
        return if (resolvedMileage == local.mileageKm) local else local.copy(mileageKm = resolvedMileage)
    }
    return local.copy(
        name = archived.name,
        make = archived.make,
        model = archived.model,
        licensePlate = archived.licensePlate,
        fuelType = FuelType.valueOf(archived.fuelType),
        mileageKm = resolvedMileage,
        // Primary status belongs to the destination garage, not the source device.
        isPrimary = local.isPrimary,
        bluetoothDevices = archived.bluetoothDevices.map { BtDevice(it.address, it.name) },
        year = archived.year,
        fuelEconomy = archived.fuelEconomy,
        tankSize = archived.tankSize,
        updatedAtEpochMs = archivedFreshness,
    )
}

private fun RideBackupManifest.preview() =
    RideBackupImportPreview(createdAtEpochMs, rides.size, vehicles.size, savedAddresses.size, refuels.size)

private fun RideBackupManifest.file(rideId: Long, role: String): BackupFile =
    files.single { it.rideArchiveId == rideId && it.role == role }

private fun BackupRide.toEntity(
    vehicleId: Long?,
    startAddressId: Long?,
    endAddressId: Long?,
    sampleFile: String,
) = Ride(
    vehicleId = vehicleId,
    startedAtEpochMs = startedAtEpochMs,
    startedElapsedNanos = startedElapsedNanos,
    endedAtEpochMs = endedAtEpochMs,
    startLat = startLat,
    startLon = startLon,
    endLat = endLat,
    endLon = endLon,
    distanceMeters = distanceMeters,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    sampleFile = sampleFile,
    startAddress = startAddress,
    endAddress = endAddress,
    startAddressId = startAddressId,
    endAddressId = endAddressId,
)
