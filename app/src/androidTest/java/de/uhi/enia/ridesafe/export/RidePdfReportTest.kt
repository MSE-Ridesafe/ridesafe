package de.uhi.enia.ridesafe.export

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class RidePdfReportTest {
    @Test
    fun manyJourneysProduceReadableMultiPageReport() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("ride_pdf_report_", ".pdf", context.cacheDir)
        try {
            val journeys =
                List(30) { index ->
                    RideExportJourney(
                        vehicle = "A very long vehicle title number $index",
                        startedAtEpochMs = 1_700_000_000_000L + index * 60_000,
                        endedAtEpochMs = 1_700_000_060_000L + index * 60_000,
                        durationMs = 60_000,
                        startAddress = "A long start address that must wrap cleanly across the available report width $index",
                        endAddress = "A long end address that must wrap cleanly across the available report width $index",
                        distanceMeters = 1_000.0,
                    )
                }
            RidePdfReport().write(file, journeys, LocalDate.of(2026, 8, 22), UnitSystemSetting.METRIC)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> assertTrue(renderer.pageCount > 1) }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun combinedJourneyWithManyNestedRidesPaginates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("ride_pdf_nested_", ".pdf", context.cacheDir)
        try {
            val children =
                List(24) { index ->
                    RideExportItem(
                        startedAtEpochMs = 1_700_000_000_000L + index * 120_000,
                        endedAtEpochMs = 1_700_000_060_000L + index * 120_000,
                        durationMs = 60_000,
                        startAddress = "Nested start address $index with enough text to wrap cleanly",
                        endAddress = "Nested end address $index with enough text to wrap cleanly",
                        distanceMeters = 1_000.0,
                    )
                }
            val journey =
                RideExportJourney(
                    vehicle = "Test combined vehicle",
                    startedAtEpochMs = children.first().startedAtEpochMs,
                    endedAtEpochMs = children.last().endedAtEpochMs,
                    durationMs = children.sumOf { it.durationMs ?: 0L },
                    startAddress = children.first().startAddress,
                    endAddress = children.last().endAddress,
                    distanceMeters = children.sumOf { it.distanceMeters ?: 0.0 },
                    individualRides = children,
                )

            RidePdfReport().write(file, listOf(journey), LocalDate.of(2026, 8, 22), UnitSystemSetting.METRIC)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> assertTrue(renderer.pageCount > 1) }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun mediaStorePublishesPdfAndUsesDuplicateSafeName() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("ride_pdf_store_", ".pdf", context.cacheDir)
        val date = LocalDate.of(2099, 12, 31)
        val desired = exportFileName(date)
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val created = mutableListOf<android.net.Uri>()
        try {
            val journey =
                RideExportJourney("Test vehicle", 1_700_000_000_000L, 1_700_000_060_000L, 60_000, null, null, null)
            RidePdfReport().write(file, listOf(journey), date, UnitSystemSetting.METRIC)
            val firstSaved = saveToDownloads(context, file, date)
            val secondSaved = saveToDownloads(context, file, date)
            assertTrue(firstSaved.fileName == desired)
            assertTrue(secondSaved.fileName == desired.removeSuffix(".pdf") + "_2.pdf")
            val firstOpen = buildOpenExportIntent(firstSaved)
            val secondOpen = buildOpenExportIntent(secondSaved)
            assertTrue(firstOpen.action == Intent.ACTION_VIEW)
            assertTrue(firstOpen.type == "application/pdf")
            assertTrue(firstOpen.data == firstSaved.uri)
            assertTrue(firstOpen.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(secondOpen.data == secondSaved.uri)
            assertTrue(firstOpen.identifier != secondOpen.identifier)

            resolver
                .query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} IN (?, ?)",
                    arrayOf(desired, desired.removeSuffix(".pdf") + "_2.pdf"),
                    null,
                )!!
                .use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val pending = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    while (cursor.moveToNext()) {
                        assertTrue(cursor.getInt(pending) == 0)
                        created += android.content.ContentUris.withAppendedId(collection, cursor.getLong(id))
                    }
                }
            assertTrue(created.size == 2)
            resolver.openFileDescriptor(created.first(), "r")!!.use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> assertTrue(renderer.pageCount == 1) }
            }
        } finally {
            created.forEach { resolver.delete(it, null, null) }
            file.delete()
        }
    }

    @Test
    fun mediaStorePublishesCsvWithCsvMimeAndExactOpenUri() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("ride_csv_store_", ".csv", context.cacheDir)
        val date = LocalDate.of(2098, 12, 31)
        val resolver = context.contentResolver
        var created: android.net.Uri? = null
        try {
            file.writeText("Type,Parent\r\nStandalone,\r\n", Charsets.UTF_8)
            val saved = saveToDownloads(context, file, date, RideExportFormat.CSV)
            created = saved.uri
            assertTrue(saved.fileName == exportFileName(date, RideExportFormat.CSV))
            val open = buildOpenExportIntent(saved)
            assertTrue(open.action == Intent.ACTION_VIEW)
            assertTrue(open.type == "text/csv")
            assertTrue(open.data == saved.uri)
            assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

            resolver
                .query(
                    saved.uri,
                    arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.IS_PENDING),
                    null,
                    null,
                    null,
                )!!
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) == "text/csv")
                    assertTrue(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)) == 0)
                }
        } finally {
            created?.let { resolver.delete(it, null, null) }
            file.delete()
        }
    }
}
