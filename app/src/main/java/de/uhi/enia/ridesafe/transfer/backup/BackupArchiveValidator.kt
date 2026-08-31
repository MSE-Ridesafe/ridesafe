package de.uhi.enia.ridesafe.transfer.backup

import com.google.maps.android.PolyUtil
import de.uhi.enia.ridesafe.data.entity.FuelType
import de.uhi.enia.ridesafe.data.entity.RideEventType
import de.uhi.enia.ridesafe.data.entity.SavedPlaceKind
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Reference reader: checks every export, gates every import, and is the contract tests hold both to. */
internal object RideBackupArchiveValidator {
    /**
     * @param onRide called once per ride, after its raw entry has been read, with how many rides the
     * archive holds — which a caller cannot know before the manifest is decoded in here. This is not
     * a suspend function, so a caller that needs the read to be cancellable checks its own context.
     */
    fun validate(
        archive: File,
        onRide: (rides: Int) -> Unit = {},
    ): RideBackupManifest {
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
                        RAW_ROLE -> {
                            zip.getInputStream(entry).use(::validateRawSamples)
                            onRide(manifest.rides.size)
                        }

                        ROUTE_ROLE -> {
                            zip.getInputStream(entry).use(::validateEncodedRoute)
                        }
                    }
                }
                return manifest
            }
        } catch (failure: RideBackupValidationException) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled // a cancelled export is not a corrupt archive; let it unwind
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

/**
 * Checks that [input] is a complete gzip stream of non-blank NDJSON records. Draining the stream to
 * its end is what verifies gzip's CRC32 and length trailer, so a file truncated by a crash
 * mid-recording is caught — the one corruption a hash cannot catch, because the short file hashes
 * correctly.
 *
 * The records themselves are not deserialized, in either direction. A backup's fidelity rests on
 * the manifest's per-file SHA-256 and on this gzip framing: the `.gz` is archived and restored
 * byte-for-byte, so a file that passes both is the file that was recorded. Deserializing each
 * record would only add "and every reading matches the current schema", at roughly twenty times
 * the cost of the framing scan over some fourteen million records — and a reading that failed it
 * would be skipped by [de.uhi.enia.ridesafe.data.file.readRideLocations] and
 * [de.uhi.enia.ridesafe.data.file.forEachSampleInTimeOrder] anyway, exactly as a corrupt
 * record in a locally recorded file already is.
 */
internal fun validateRawSamples(input: InputStream) {
    try {
        GZIPInputStream(input).use(::scanRawSampleRecords)
    } catch (failure: RideBackupValidationException) {
        throw failure
    } catch (failure: Exception) {
        throw RideBackupValidationException("Raw sample gzip stream is corrupt", failure)
    }
}

private const val SPACE = ' '.code.toByte()
private const val NEWLINE = '\n'.code.toByte()

/** Matches [String.isBlank] closely enough: any byte above space, and every non-ASCII byte, is content. */
private fun isContent(byte: Byte) = byte !in 0..SPACE

private fun scanRawSampleRecords(input: InputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var record = 1
    var blank = true
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        for (index in 0 until read) {
            val byte = buffer[index]
            if (byte == NEWLINE) {
                if (blank) fail("Raw sample record $record is blank")
                record++
                blank = true
            } else if (isContent(byte)) {
                blank = false
            }
        }
    }
}

internal fun validateEncodedRoute(input: InputStream) {
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
