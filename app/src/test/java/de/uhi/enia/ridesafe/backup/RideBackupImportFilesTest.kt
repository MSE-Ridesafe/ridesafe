package de.uhi.enia.ridesafe.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Import extracts into the rides directory itself, so its staged files live beside real ones. */
class RideBackupImportFilesTest {
    private val directory: File = Files.createTempDirectory("ridesafe_rides_").toFile().apply { deleteOnExit() }

    @Test
    fun sweepRemovesStagedFilesAndLeavesRealRideFilesAlone() {
        val staged = stagedImportFile(directory).apply { writeText("half an import") }
        val samples = File(directory, "ride_12345.ndjson.gz").apply { writeText("samples") }
        val route = File(directory, "ride_12345.route.v2").apply { writeText("route") }

        sweepStaleStagedFiles(directory)

        assertFalse(staged.exists())
        assertTrue(samples.exists())
        assertTrue(route.exists())
    }

    @Test
    fun stagedNamesCannotBeMistakenForRouteSidecars() {
        // pruneStaleRoutes deletes anything containing ".route" that is not the current version.
        assertFalse(stagedImportFile(directory).name.contains(".route"))
        // Distinct per entry, so two entries of one import never collide.
        assertFalse(stagedImportFile(directory).name == stagedImportFile(directory).name)
    }

    @Test
    fun extractedEntriesAreCheckedAgainstTheManifestsSizeAndHash() {
        val extracted = stagedImportFile(directory).apply { writeText("samples") }
        val sha = "a".repeat(64)
        val descriptor =
            BackupFile(1, "raw_samples", "required_source", "included", "data/rides/1/samples.ndjson.gz", "application/x-ndjson")
                .copy(sizeBytes = extracted.length(), sha256 = sha)

        requireExtractedFileMatches(descriptor, extracted, sha.uppercase()) // hex case must not matter

        assertThrows(RideBackupValidationException::class.java) {
            requireExtractedFileMatches(descriptor.copy(sizeBytes = extracted.length() + 1), extracted, sha)
        }
        assertThrows(RideBackupValidationException::class.java) {
            requireExtractedFileMatches(descriptor, extracted, "b".repeat(64))
        }
    }

    @Test
    fun publishRenamesInPlaceAndRefusesToOverwrite() {
        val staged = stagedImportFile(directory).apply { writeText("samples") }
        val destination = File(directory, "ride_import_abc_1.ndjson.gz")

        publishImportFile(staged, destination)

        assertFalse(staged.exists())
        assertEquals("samples", destination.readText())

        val second = stagedImportFile(directory).apply { writeText("other") }
        assertThrows(IllegalArgumentException::class.java) { publishImportFile(second, destination) }
        assertEquals("samples", destination.readText())
    }
}
