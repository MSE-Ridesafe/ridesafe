package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.rides.RideDataCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class RideZipBackupTest {
    @Test
    fun referenceReaderAcceptsFinishedArchiveWithCanonicalPathsAndStoredGzip() =
        runBlocking {
            val raw = validRawFile()
            val archive = temporary(".zip")
            val rawSource = source(raw, rawDescriptor(raw))
            val manifest = manifest(files = listOf(rawSource.metadata, absentRouteDescriptor()))

            writeRideBackupZip(archive, manifest, listOf(rawSource, absentSource()))

            val parsed = RideBackupArchiveValidator.validate(archive)
            assertEquals(manifest, parsed)
            ZipFile(archive).use { zip ->
                assertEquals(ZipEntry.STORED, zip.getEntry("data/rides/42/samples.ndjson.gz").method)
                assertNotNull(zip.getEntry("manifest.json"))
                assertFalse(zip.entries().asSequence().any { it.name == "data/rides/42/route.v2" })
            }
        }

    @Test
    fun corruptGzipIsRejected(): Unit =
        runBlocking {
            val corrupt = temporary(".gz").apply { writeText("not gzip") }
            val rawSource = source(corrupt, rawDescriptor(corrupt))
            val archive = temporary(".zip")
            writeRideBackupZip(archive, manifest(files = listOf(rawSource.metadata, absentRouteDescriptor())), listOf(rawSource, absentSource()))

            assertThrows(RideBackupValidationException::class.java) { RideBackupArchiveValidator.validate(archive) }
        }

    @Test
    fun corruptEncodedRouteIsRejected(): Unit =
        runBlocking {
            val raw = validRawFile()
            val route = temporary(".route.v2").apply { writeText("?") }
            val rawSource = source(raw, rawDescriptor(raw))
            val routeSource = source(route, includedRouteDescriptor(route))
            val archive = temporary(".zip")
            writeRideBackupZip(archive, manifest(files = listOf(rawSource.metadata, routeSource.metadata)), listOf(rawSource, routeSource))

            assertThrows(RideBackupValidationException::class.java) { RideBackupArchiveValidator.validate(archive) }
        }

    @Test
    fun missingRequiredRawDescriptorIsRejected() {
        assertThrows(RideBackupValidationException::class.java) {
            RideBackupArchiveValidator.validateManifest(manifest(files = listOf(absentRouteDescriptor())))
        }
    }

    @Test
    fun activeAndMissingSelectedRidesAreRejected() {
        val active = Ride(id = 42, startedAtEpochMs = 1, startedElapsedNanos = 2, sampleFile = "ride.gz")
        assertThrows(IllegalArgumentException::class.java) { requireFinishedSelectedRides(listOf(42), listOf(active)) }
        assertThrows(IllegalArgumentException::class.java) { requireFinishedSelectedRides(listOf(42, 43), listOf(active.copy(endedAtEpochMs = 3))) }
    }

    @Test
    fun checksumMismatchIsRejectedByFinishedArchiveReader(): Unit =
        runBlocking {
            val raw = validRawFile()
            val badDescriptor = rawDescriptor(raw).copy(sha256 = "0".repeat(64))
            val rawSource = source(raw, badDescriptor)
            val archive = temporary(".zip")
            writeRideBackupZip(archive, manifest(files = listOf(badDescriptor, absentRouteDescriptor())), listOf(rawSource, absentSource()))

            assertThrows(RideBackupValidationException::class.java) { RideBackupArchiveValidator.validate(archive) }
        }

    @Test
    fun cancelledExportStopsBeforeWriting() {
        val raw = validRawFile()
        val rawSource = source(raw, rawDescriptor(raw))
        val archive = temporary(".zip")
        assertThrows(CancellationException::class.java) {
            runBlocking {
                cancel()
                writeRideBackupZip(archive, manifest(files = listOf(rawSource.metadata, absentRouteDescriptor())), listOf(rawSource, absentSource()))
            }
        }
    }

    @Test
    fun duplicateAndUnsafePathsAreRejected() {
        val raw = validRawFile()
        val descriptor = rawDescriptor(raw)
        assertThrows(RideBackupValidationException::class.java) {
            RideBackupArchiveValidator.validateManifest(manifest(files = listOf(descriptor, descriptor, absentRouteDescriptor())))
        }
        assertThrows(RideBackupValidationException::class.java) {
            RideBackupArchiveValidator.validateManifest(
                manifest(files = listOf(descriptor.copy(path = "../samples.ndjson.gz"), absentRouteDescriptor())),
            )
        }
    }

    @Test
    fun brokenReferencesAndUnknownEnumsAreRejected() {
        val raw = validRawFile()
        val files = listOf(rawDescriptor(raw), absentRouteDescriptor())
        assertThrows(RideBackupValidationException::class.java) {
            RideBackupArchiveValidator.validateManifest(
                manifest(files = files).copy(rides = listOf(backupRide().copy(vehicleArchiveId = 99))),
            )
        }
        assertThrows(RideBackupValidationException::class.java) {
            RideBackupArchiveValidator.validateManifest(
                manifest(files = files).copy(rideEvents = listOf(backupEvent().copy(type = "FUTURE_EVENT"))),
            )
        }
    }

    @Test
    fun manifestRoundTripsAndIgnoresUnknownFieldsButRejectsUnsupportedSchemas() {
        val raw = validRawFile()
        val original = manifest(files = listOf(rawDescriptor(raw), absentRouteDescriptor()))
        val encoded = encodeRideBackupManifest(original)

        assertEquals(original, decodeRideBackupManifest(encoded))
        assertEquals(original, decodeRideBackupManifest(encoded.dropLast(1) + ",\n  \"futureField\": true\n}"))
        assertThrows(RideBackupValidationException::class.java) {
            decodeRideBackupManifest(encoded.replaceFirst("\"schemaVersion\": 2", "\"schemaVersion\": 3"))
        }
        assertThrows(RideBackupValidationException::class.java) {
            decodeRideBackupManifest(encoded.replaceFirst("\"schemaVersion\": 2", "\"schemaVersion\": 1"))
        }
    }

    @Test
    fun exportWaitsForConcurrentAnalysisOfTheSameRide() =
        runBlocking {
            val analysisEntered = CompletableDeferred<Unit>()
            val releaseAnalysis = CompletableDeferred<Unit>()
            val analysis =
                launch {
                    RideDataCoordinator.withRide(42) {
                        analysisEntered.complete(Unit)
                        releaseAnalysis.await()
                    }
                }
            analysisEntered.await()
            val exportEntered = CompletableDeferred<Unit>()
            val export = async { RideDataCoordinator.withRides(listOf(42)) { exportEntered.complete(Unit) } }
            yield()
            assertFalse(exportEntered.isCompleted)
            releaseAnalysis.complete(Unit)
            export.await()
            analysis.join()
            assertTrue(exportEntered.isCompleted)
        }

    private fun manifest(files: List<BackupFile>): RideBackupManifest =
        RideBackupManifest(
            createdAtEpochMs = 1_725_000_000_000,
            producer = BackupProducer("de.uhi.enia.ridesafe", "1.0", 1, "android", "16", 36),
            sourceDatabaseVersion = 17,
            processingVersions = BackupProcessingVersions(2, 1, 8, 1),
            logicalSelections = listOf(BackupLogicalSelection("selection-1", listOf(42))),
            mergeGroups = emptyList(),
            rides = listOf(backupRide()),
            vehicles = emptyList(),
            savedAddresses = emptyList(),
            rideEvents = emptyList(),
            analysisStates = listOf(BackupAnalysisState(42, "route", 2)),
            refuels = emptyList(),
            files = files,
        )

    private fun backupRide() =
        BackupRide(
            archiveId = 42,
            vehicleArchiveId = null,
            mergeGroupArchiveId = null,
            startedAtEpochMs = 1000,
            startedElapsedNanos = 2000,
            endedAtEpochMs = 3000,
            startLat = 52.0,
            startLon = 9.0,
            endLat = 52.1,
            endLon = 9.1,
            distanceMeters = 1000.0,
            avgSpeedMps = 10.0,
            maxSpeedMps = 12.0,
            startAddress = null,
            endAddress = null,
            startSavedAddressArchiveId = null,
            endSavedAddressArchiveId = null,
        )

    private fun backupEvent() = BackupRideEvent(5, 42, "BRAKING", 1, 2, 0.3, 1.2, 0.2, 10.0, null, null)

    private fun validRawFile(): File =
        temporary(".ndjson.gz").apply {
            GZIPOutputStream(outputStream()).bufferedWriter(Charsets.UTF_8).use {
                it.write("{\"ty\":\"loc\",\"t\":2,\"lat\":52.0,\"lon\":9.0,\"alt\":80.0,\"speed\":10.0,\"bearing\":0.0,\"accuracy\":4.0}")
                it.newLine()
                // Intentionally older: the archive contract does not claim global timestamp order.
                it.write("{\"ty\":\"mot\",\"t\":1,\"sensor\":\"ACCEL\",\"x\":0.0,\"y\":0.0,\"z\":9.8,\"w\":null}")
                it.newLine()
            }
        }

    private fun rawDescriptor(file: File): BackupFile =
        BackupFile(42, "raw_samples", "required_source", "included", "data/rides/42/samples.ndjson.gz", "application/x-ndjson", "gzip", file.length(), sha256(file))

    private fun absentRouteDescriptor() =
        BackupFile(42, "processed_route", "optional_regenerable_derived", "absent", "data/rides/42/route.v2", "application/vnd.google.polyline", "google-encoded-polyline-1e5", null, null)

    private fun includedRouteDescriptor(file: File) = absentRouteDescriptor().copy(status = "included", sizeBytes = file.length(), sha256 = sha256(file))

    private fun absentSource() = RideBackupSourceFile(absentRouteDescriptor(), temporary(".absent"), 0)

    private fun source(file: File, descriptor: BackupFile): RideBackupSourceFile {
        val crc = CRC32().apply { update(file.readBytes()) }
        return RideBackupSourceFile(descriptor, file, crc.value)
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xff)
        }

    private fun temporary(suffix: String): File = File.createTempFile("ridesafe_backup_test_", suffix).apply { deleteOnExit() }
}
