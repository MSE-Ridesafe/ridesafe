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

/**
 * How far a restore has got. Both halves need one: checking a backup reads the whole archive once,
 * importing it reads the archive again and writes every ride file out.
 *
 * [passes] counts per-ride passes rather than rides, because the passes cannot be interleaved —
 * every entry is verified before any is extracted — so a per-ride counter would run 1..n twice over.
 * [rides] is 0 until the archive's manifest has been read, which the dialog shows as an
 * indeterminate ring.
 */
data class RideBackupImportProgress(
    val passes: Int = 0,
    val rides: Int = 0,
    val passesPerRide: Int = 1,
) {
    private val total get() = rides * passesPerRide

    val fraction: Float get() = if (total == 0) 0f else (passes.toFloat() / total).coerceIn(0f, 1f)

    val ridesDone: Int get() = if (passesPerRide == 0) 0 else (passes / passesPerRide).coerceAtMost(rides)
}

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

private const val STAGED_PREFIX = ".ridesafe_import_"
private const val STAGED_SUFFIX = ".tmp"

/**
 * Entries are extracted straight into the rides directory under this name and renamed into place
 * once the database agrees, rather than being written to a staging directory and copied in. One
 * write instead of two, and because both names are now in the same directory the rename is always
 * a true atomic move. The name deliberately contains no ".route", so stale-route pruning cannot
 * mistake an in-flight import for an old sidecar.
 */
internal fun stagedImportFile(directory: File) = File(directory, "$STAGED_PREFIX${UUID.randomUUID()}$STAGED_SUFFIX")

internal fun sweepStaleStagedFiles(directory: File) {
    directory
        .listFiles { file -> file.isFile && file.name.startsWith(STAGED_PREFIX) && file.name.endsWith(STAGED_SUFFIX) }
        ?.forEach { it.delete() }
}

/** Atomic rename of an already-fsynced staged file; a crash leaves either nothing or the whole file. */
internal fun publishImportFile(
    staged: File,
    destination: File,
) {
    require(!destination.exists()) { "Import destination already exists: ${destination.name}" }
    Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
}

/**
 * A backup already copied out of its content URI, held from the preview until the user decides.
 * Confirming imports [archive] as it stands rather than reading the URI a second time; dismissing
 * discards it. Whichever happens, the file is deleted — and [RideBackupImporter.inspect] sweeps any
 * that an app death left behind.
 */
internal class RideBackupImportCandidate internal constructor(
    val preview: RideBackupImportPreview,
    internal val archive: File,
)

internal class RideBackupImporter(
    private val app: Application,
    private val db: RidesafeDatabase = RidesafeDatabase.getInstance(app),
) {
    /** Preview only, so the cheap record check: the deep one runs before anything is written. */
    suspend fun inspect(
        uri: Uri,
        onProgress: (RideBackupImportProgress) -> Unit = {},
    ): RideBackupImportCandidate =
        withContext(Dispatchers.IO) {
            // Both sweeps belong here rather than in the import: nothing else is in flight while a
            // backup is being previewed, so this cannot delete a running import's own staged files.
            sweepStaleArchives(app.cacheDir)
            sweepStaleStagedFiles(ridesDir(app))
            val archive = copyToCache(uri)
            val context = currentCoroutineContext()
            var passes = 0
            try {
                val manifest =
                    RideBackupArchiveValidator.validate(archive) { rides ->
                        context.ensureActive()
                        onProgress(RideBackupImportProgress(++passes, rides))
                    }
                RideBackupImportCandidate(manifest.preview(), archive)
            } catch (failure: Throwable) {
                archive.delete()
                throw failure
            }
        }

    suspend fun import(
        candidate: RideBackupImportCandidate,
        onProgress: (RideBackupImportProgress) -> Unit = {},
    ): RideBackupImportResult =
        try {
            importArchive(candidate.archive, onProgress)
        } finally {
            candidate.archive.delete()
        }

    /** The user backed out of the confirmation; the copy made for the preview is no longer wanted. */
    fun discard(candidate: RideBackupImportCandidate) {
        candidate.archive.delete()
    }

    internal suspend fun importArchive(
        archive: File,
        onProgress: (RideBackupImportProgress) -> Unit = {},
    ): RideBackupImportResult =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            var passes = 0
            // Verify every entry, then extract every entry: two passes over each ride.
            val report = { rides: Int -> onProgress(RideBackupImportProgress(++passes, rides, PASSES_PER_RIDE)) }
            val manifest =
                RideBackupArchiveValidator.validate(archive, decodeRecords = true) { rides ->
                    context.ensureActive()
                    report(rides)
                }
            val destinationDirectory = ridesDir(app).apply { mkdirs() }
            // Extraction writes the bytes; the transaction below only renames them, so the database
            // write lock is not held across a quarter of a gigabyte of copying and fsyncing.
            val stagedFiles = mutableMapOf<String, File>()
            val published = mutableListOf<File>()
            try {
                extractIncludedFiles(archive, manifest, destinationDirectory, stagedFiles) { report(manifest.rides.size) }
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
                // Whatever the transaction did not rename into place is still under its staged name.
                stagedFiles.values.forEach { it.delete() }
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
        val destinationDirectory = ridesDir(app)
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

    private fun publish(
        staged: File,
        destination: File,
        published: MutableList<File>,
    ) {
        publishImportFile(staged, destination)
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

    /** Fills [staged] as it goes, so a failure part-way through still tells the caller what to clean up. */
    private suspend fun extractIncludedFiles(
        archive: File,
        manifest: RideBackupManifest,
        directory: File,
        staged: MutableMap<String, File>,
        onRide: () -> Unit,
    ) {
        ZipFile(archive).use { zip ->
            manifest.files.filter { it.status == "included" }.forEach { descriptor ->
                currentCoroutineContext().ensureActive()
                val target = stagedImportFile(directory)
                staged[descriptor.path] = target
                FileOutputStream(target).use { output ->
                    zip.getInputStream(zip.getEntry(descriptor.path)).use { input -> copyCancellable(input, output) }
                    output.fd.sync()
                }
                if (descriptor.role == RAW_ROLE) onRide()
            }
        }
    }

    private suspend fun copyToCache(uri: Uri): File {
        val local = File.createTempFile(ARCHIVE_PREFIX, ".zip", app.cacheDir)
        try {
            val input = app.contentResolver.openInputStream(uri) ?: error("The selected backup cannot be opened")
            input.use { source -> local.outputStream().buffered().use { target -> copyCancellable(source, target) } }
            return local
        } catch (failure: Throwable) {
            local.delete()
            throw failure
        }
    }
}

/** Verify, then extract: a restore reads every ride twice. */
private const val PASSES_PER_RIDE = 2

private const val ARCHIVE_PREFIX = "ridesafe_import_"

private fun sweepStaleArchives(cacheDir: File) {
    cacheDir.listFiles { file -> file.isFile && file.name.startsWith(ARCHIVE_PREFIX) }?.forEach { it.delete() }
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
