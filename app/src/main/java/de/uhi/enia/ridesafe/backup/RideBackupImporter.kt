package de.uhi.enia.ridesafe.backup

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
import de.uhi.enia.ridesafe.data.normalizeForMatching
import de.uhi.enia.ridesafe.rides.processing.ROUTE_VERSION
import de.uhi.enia.ridesafe.rides.processing.processedRouteFile
import de.uhi.enia.ridesafe.rides.recording.ridesDir
import de.uhi.enia.ridesafe.util.copyCancellable
import de.uhi.enia.ridesafe.util.haversineMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

data class RideBackupImportPreview(
    val createdAtEpochMs: Long,
    val rides: Int,
    val vehicles: Int,
    val savedAddresses: Int,
    val refuels: Int,
)

data class RideBackupImportCount(
    val imported: Int,
    val alreadyPresent: Int,
)

data class RideBackupImportResult(
    val rides: RideBackupImportCount,
    val vehicles: RideBackupImportCount,
    val savedAddresses: RideBackupImportCount,
    val refuels: RideBackupImportCount,
)

private data class RideImportMapping(
    val ids: Map<Long, Long>,
    val insertedArchiveIds: Set<Long>,
)

private data class EntityImportMapping(
    val ids: Map<Long, Long>,
    val insertedArchiveIds: Set<Long>,
)

/** Copy-then-atomic-rename, so a cancelled or crashed import never leaves a half-written file. */
private suspend fun publishImportFile(
    source: File,
    destination: File,
) {
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
) {
    suspend fun inspect(uri: Uri): RideBackupImportPreview =
        withLocalArchive(uri) { archive ->
            val manifest = RideBackupArchiveValidator.validate(archive)
            manifest.preview()
        }

    suspend fun import(uri: Uri): RideBackupImportResult = withLocalArchive(uri) { archive -> importArchive(archive) }

    internal suspend fun importArchive(archive: File): RideBackupImportResult =
        withContext(Dispatchers.IO) {
            val manifest = RideBackupArchiveValidator.validate(archive)
            val staging = Files.createTempDirectory(app.cacheDir.toPath(), "ridesafe_import_").toFile()
            val published = mutableListOf<File>()
            try {
                val stagedFiles = extractIncludedFiles(archive, manifest, staging)
                val token = UUID.randomUUID().toString().replace("-", "")
                val sampleNames = manifest.rides.associate { it.archiveId to "ride_import_${token}_${it.archiveId}.ndjson.gz" }
                val result =
                    db.withTransaction {
                        val vehicleImport = insertVehicles(manifest)
                        val addressImport = insertAddresses(manifest)
                        val rideImport = insertRides(manifest, vehicleImport.ids, addressImport.ids, sampleNames)
                        restoreMergeGroups(manifest, rideImport.ids)
                        insertEvents(manifest, rideImport.ids, rideImport.insertedArchiveIds)
                        insertAnalysisStates(manifest, rideImport.ids, rideImport.insertedArchiveIds)
                        val importedRefuels = insertRefuels(manifest, vehicleImport.ids, rideImport.ids)
                        publishFiles(
                            manifest,
                            stagedFiles,
                            rideImport.ids,
                            sampleNames,
                            rideImport.insertedArchiveIds,
                            published,
                        )
                        RideBackupImportResult(
                            rides = importCount(manifest.rides.size, rideImport.insertedArchiveIds.size),
                            vehicles = importCount(manifest.vehicles.size, vehicleImport.insertedArchiveIds.size),
                            savedAddresses = importCount(manifest.savedAddresses.size, addressImport.insertedArchiveIds.size),
                            refuels = importCount(manifest.refuels.size, importedRefuels),
                        )
                    }
                result
            } catch (failure: Exception) {
                published.forEach { it.delete() }
                throw failure
            } finally {
                staging.deleteRecursively()
            }
        }

    private suspend fun insertVehicles(manifest: RideBackupManifest): EntityImportMapping {
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
                compareBy({ it.archiveId != primaryArchiveId }, { vehicleFreshness(it, manifest) }, { it.archiveId }),
            )
        val mappings = mutableMapOf<Long, Long>()
        val insertedArchiveIds = mutableSetOf<Long>()
        ordered.forEach { archived ->
            val matched = findMatchingVehicle(archived, existing)
            if (matched == null) {
                val inserted = archived.toVehicle(archived.archiveId == primaryArchiveId, manifest)
                val id = dao.insert(inserted)
                existing += inserted.copy(id = id)
                mappings[archived.archiveId] = id
                insertedArchiveIds += archived.archiveId
            } else {
                val resolved = resolveVehicleConflict(matched, archived, manifest)
                if (resolved != matched) {
                    dao.update(resolved)
                    existing[existing.indexOfFirst { it.id == matched.id }] = resolved
                }
                mappings[archived.archiveId] = matched.id
            }
        }
        return EntityImportMapping(mappings, insertedArchiveIds)
    }

    private suspend fun insertAddresses(manifest: RideBackupManifest): EntityImportMapping {
        val dao = db.savedAddressDao()
        val existing = dao.all().toMutableList()
        val mappings = mutableMapOf<Long, Long>()
        val insertedArchiveIds = mutableSetOf<Long>()
        manifest.savedAddresses.forEach { archived ->
            val matches = existing.filter { it.matches(archived) }.sortedBy(SavedAddress::id)
            val retained = matches.firstOrNull()
            if (retained == null) {
                val inserted = archived.toSavedAddress()
                val id = dao.insert(inserted)
                existing += inserted.copy(id = id)
                mappings[archived.archiveId] = id
                insertedArchiveIds += archived.archiveId
            } else {
                // Repair duplicates made by older importer versions. All existing ride references
                // are moved before the redundant address rows are removed.
                val duplicates = matches.drop(1)
                duplicates.forEach { duplicate ->
                    db.rideDao().replaceSavedAddressReferences(duplicate.id, retained.id)
                }
                if (duplicates.isNotEmpty()) {
                    dao.deleteByIds(duplicates.map(SavedAddress::id))
                    existing.removeAll { candidate -> duplicates.any { it.id == candidate.id } }
                }
                mappings[archived.archiveId] = retained.id
            }
        }
        return EntityImportMapping(mappings, insertedArchiveIds)
    }

    private suspend fun insertRides(
        manifest: RideBackupManifest,
        vehicleIds: Map<Long, Long>,
        addressIds: Map<Long, Long>,
        sampleNames: Map<Long, String>,
    ): RideImportMapping {
        val dao = db.rideDao()
        val existing = dao.all()
        val byUuid = existing.associateBy { it.rideUuid.lowercase(Locale.ROOT) }.toMutableMap()
        val fallbackHashes =
            manifest.rides
                .filter { archived ->
                    archived.rideUuid?.lowercase(Locale.ROOT)?.let(byUuid::containsKey) != true
                }.mapNotNull { manifest.file(it.archiveId, "raw_samples").sha256?.lowercase(Locale.ROOT) }
                .toSet()
        val byRawHash = existingRidesByRawHash(existing, fallbackHashes).toMutableMap()
        val mappings = mutableMapOf<Long, Long>()
        val inserted = mutableSetOf<Long>()

        manifest.rides.forEach { archived ->
            val matched =
                archived.rideUuid?.let { byUuid[it.lowercase(Locale.ROOT)] }
                    ?: manifest
                        .file(archived.archiveId, "raw_samples")
                        .sha256
                        ?.lowercase(Locale.ROOT)
                        ?.let(byRawHash::get)
            if (matched != null) {
                mappings[archived.archiveId] = matched.id
            } else {
                val entity =
                    archived.toEntity(
                        vehicleId = archived.vehicleArchiveId?.let(vehicleIds::getValue),
                        startAddressId = archived.startSavedAddressArchiveId?.let(addressIds::getValue),
                        endAddressId = archived.endSavedAddressArchiveId?.let(addressIds::getValue),
                        sampleFile = sampleNames.getValue(archived.archiveId),
                    )
                val id = dao.insert(entity)
                val persisted = entity.copy(id = id)
                mappings[archived.archiveId] = id
                inserted += archived.archiveId
                byUuid[persisted.rideUuid.lowercase(Locale.ROOT)] = persisted
                manifest
                    .file(archived.archiveId, "raw_samples")
                    .sha256
                    ?.lowercase(Locale.ROOT)
                    ?.let { byRawHash.putIfAbsent(it, persisted) }
            }
        }
        return RideImportMapping(mappings, inserted)
    }

    /** Raw samples are required and content-addressed, making their hash a safe legacy identity. */
    private suspend fun existingRidesByRawHash(
        rides: List<Ride>,
        wantedHashes: Set<String>,
    ): Map<String, Ride> {
        if (wantedHashes.isEmpty()) return emptyMap()
        val root = ridesDir(app).canonicalFile
        val matches = mutableMapOf<String, Ride>()
        rides.filter { it.endedAtEpochMs != null }.sortedBy(Ride::id).forEach { ride ->
            currentCoroutineContext().ensureActive()
            val candidate = runCatching { File(root, ride.sampleFile).canonicalFile }.getOrNull()
            if (candidate?.parentFile != root || !candidate.isFile) return@forEach
            val hash = sha256(candidate)
            if (hash in wantedHashes) matches.putIfAbsent(hash, ride)
        }
        return matches
    }

    private suspend fun restoreMergeGroups(
        manifest: RideBackupManifest,
        rideIds: Map<Long, Long>,
    ) {
        manifest.mergeGroups.forEach { group ->
            val members = group.rideArchiveIdsInStartOrder.map(rideIds::getValue)
            db.rideDao().setMergeGroup(members.min(), members)
        }
    }

    private suspend fun insertEvents(
        manifest: RideBackupManifest,
        rideIds: Map<Long, Long>,
        insertedRideArchiveIds: Set<Long>,
    ) {
        val events =
            manifest.rideEvents.filter { it.rideArchiveId in insertedRideArchiveIds }.map { event ->
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
            }
        if (events.isNotEmpty()) db.rideEventDao().insertAll(events)
    }

    private suspend fun insertAnalysisStates(
        manifest: RideBackupManifest,
        rideIds: Map<Long, Long>,
        insertedRideArchiveIds: Set<Long>,
    ) {
        val routesAvailable =
            manifest.files
                .filter { it.role == "processed_route" && it.status == "included" }
                .map(BackupFile::rideArchiveId)
                .toSet()
        manifest.analysisStates
            .filter { it.rideArchiveId in insertedRideArchiveIds }
            .filterNot {
                it.stage == "route" &&
                    (manifest.processingVersions.route != ROUTE_VERSION || it.rideArchiveId !in routesAvailable)
            }.forEach { state ->
                db.rideAnalysisDao().stamp(RideAnalysisState(rideIds.getValue(state.rideArchiveId), state.stage, state.version))
            }
    }

    private suspend fun insertRefuels(
        manifest: RideBackupManifest,
        vehicleIds: Map<Long, Long>,
        rideIds: Map<Long, Long>,
    ): Int {
        val dao = db.refuelDao()
        val existing = dao.all().toMutableList()
        var insertedCount = 0
        manifest.refuels.forEach { refuel ->
            val candidate =
                Refuel(
                    vehicleId = vehicleIds.getValue(refuel.vehicleArchiveId),
                    timestampEpochMs = refuel.timestampEpochMs,
                    fuelAmountMilliliters = refuel.fuelAmountMilliliters,
                    totalPriceMinor = refuel.totalPriceMinor,
                    currencyCode = refuel.currencyCode,
                    odometerMeters = refuel.odometerMeters,
                    isFullTank = refuel.isFullTank,
                    journeyAnchorRideId = refuel.journeyAnchorRideArchiveId?.let(rideIds::getValue),
                )
            if (existing.any { it.hasSameImportIdentity(candidate) }) return@forEach

            val id = dao.insert(candidate)
            existing += candidate.copy(id = id)
            insertedCount++
        }
        return insertedCount
    }

    private suspend fun publishFiles(
        manifest: RideBackupManifest,
        staged: Map<String, File>,
        rideIds: Map<Long, Long>,
        sampleNames: Map<Long, String>,
        insertedRideArchiveIds: Set<Long>,
        published: MutableList<File>,
    ) {
        val destinationDirectory = ridesDir(app).apply { mkdirs() }
        manifest.rides.filter { it.archiveId in insertedRideArchiveIds }.forEach { ride ->
            currentCoroutineContext().ensureActive()
            val raw = manifest.file(ride.archiveId, "raw_samples")
            publish(staged.getValue(raw.path), File(destinationDirectory, sampleNames.getValue(ride.archiveId)), published)
            val route = manifest.file(ride.archiveId, "processed_route")
            if (route.status == "included" && manifest.processingVersions.route == ROUTE_VERSION) {
                val restoredRide =
                    ride
                        .toEntity(
                            null,
                            null,
                            null,
                            sampleNames.getValue(ride.archiveId),
                        ).copy(id = rideIds.getValue(ride.archiveId))
                publish(staged.getValue(route.path), processedRouteFile(app, restoredRide), published)
            }
        }
    }

    private suspend fun publish(
        source: File,
        destination: File,
        published: MutableList<File>,
    ) {
        publishImportFile(source, destination)
        published += destination
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private suspend fun extractIncludedFiles(
        archive: File,
        manifest: RideBackupManifest,
        staging: File,
    ): Map<String, File> =
        ZipFile(archive).use { zip ->
            manifest.files
                .filter { it.status == "included" }
                .mapIndexed { index, descriptor ->
                    currentCoroutineContext().ensureActive()
                    val target = File(staging, "entry_$index")
                    zip.getInputStream(zip.getEntry(descriptor.path)).use { input ->
                        target.outputStream().buffered().use { output -> copyCancellable(input, output) }
                    }
                    descriptor.path to target
                }.toMap()
        }

    private suspend fun <T> withLocalArchive(
        uri: Uri,
        operation: suspend (File) -> T,
    ): T =
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

/**
 * Prefer stable UUID identity. If independently migrated databases assigned different UUIDs to the
 * same pre-existing car, fall back to unambiguous plate/Bluetooth evidence. Ambiguous evidence
 * deliberately creates a separate garage entry.
 */
internal fun findMatchingVehicle(
    archived: BackupVehicle,
    existing: List<Vehicle>,
): Vehicle? {
    archived.vehicleUuid?.let { uuid ->
        existing.singleOrNull { it.vehicleUuid.equals(uuid, ignoreCase = true) }?.let { return it }
    }

    val plate = normalizeForMatching(archived.licensePlate)
    val plateMatches =
        if (plate.isEmpty()) emptyList() else existing.filter { normalizeForMatching(it.licensePlate) == plate }
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
        plateMatches.isNotEmpty() && bluetoothMatches.isNotEmpty() -> {
            plateMatches.intersect(bluetoothMatches.toSet()).singleOrNull()
        }

        plateMatches.size == 1 -> {
            plateMatches.single()
        }

        bluetoothMatches.size == 1 -> {
            bluetoothMatches.single()
        }

        else -> {
            null
        }
    }
}

private fun normalizeBluetoothAddress(value: String): String? = normalizeForMatching(value).takeIf { it.length == 12 }

private val singletonSavedPlaceKinds =
    setOf(SavedPlaceKind.HOME, SavedPlaceKind.WORK, SavedPlaceKind.SCHOOL)

/**
 * Home/Work/School are product-level singletons. Other places match within their type by normalized
 * postal address or nearby coordinates; labels are presentation and may have been renamed.
 */
private fun SavedAddress.matches(archived: BackupSavedAddress): Boolean {
    val archivedKind = SavedPlaceKind.valueOf(archived.kind)
    if (kind != archivedKind) return false
    if (kind in singletonSavedPlaceKinds) return true

    val localAddress = address?.let(::normalizeForMatching).orEmpty()
    val archivedAddress = archived.address?.let(::normalizeForMatching).orEmpty()
    val sameKnownAddress = localAddress.isNotEmpty() && localAddress == archivedAddress
    val sameCoordinates = haversineMeters(latitude, longitude, archived.latitude, archived.longitude) <= 15.0
    return sameKnownAddress || sameCoordinates
}

private fun BackupSavedAddress.toSavedAddress() =
    SavedAddress(
        label = label,
        kind = SavedPlaceKind.valueOf(kind),
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        icon = icon,
        address = address,
    )

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
        vehicleType = vehicleType,
        engine = engine,
        manufacturingCountry = manufacturingCountry,
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
        vehicleType = archived.vehicleType ?: local.vehicleType,
        engine = archived.engine ?: local.engine,
        manufacturingCountry = archived.manufacturingCountry ?: local.manufacturingCountry,
        updatedAtEpochMs = archivedFreshness,
    )
}

private fun RideBackupManifest.preview() =
    RideBackupImportPreview(createdAtEpochMs, rides.size, vehicles.size, savedAddresses.size, refuels.size)

private fun importCount(
    total: Int,
    imported: Int,
) = RideBackupImportCount(imported = imported, alreadyPresent = total - imported)

/**
 * Refuel archives do not yet carry UUIDs, so their immutable business fields form their identity.
 * The journey anchor is a mutable relationship and must not turn a moved/detached refuel into a
 * second refuel during import.
 */
private fun Refuel.hasSameImportIdentity(other: Refuel): Boolean =
    vehicleId == other.vehicleId &&
        timestampEpochMs == other.timestampEpochMs &&
        fuelAmountMilliliters == other.fuelAmountMilliliters &&
        totalPriceMinor == other.totalPriceMinor &&
        currencyCode.equals(other.currencyCode, ignoreCase = true) &&
        odometerMeters == other.odometerMeters &&
        isFullTank == other.isFullTank

private fun RideBackupManifest.file(
    rideId: Long,
    role: String,
): BackupFile = files.single { it.rideArchiveId == rideId && it.role == role }

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
    rideUuid = rideUuid ?: UUID.randomUUID().toString(),
)
