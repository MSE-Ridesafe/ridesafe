package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.Vehicle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RideCsvExportTest {
    private val formatter =
        object : RideExportValueFormatter {
            override fun date(epochMs: Long) = "date-$epochMs"

            override fun time(epochMs: Long) = "time-$epochMs"

            override fun duration(durationMs: Long?) = durationMs?.let { "duration-$it" } ?: "Unavailable"

            override fun distance(distanceMeters: Double?) = distanceMeters?.let { "distance-$it" } ?: "Unavailable"
        }

    private fun item(
        start: Long,
        startAddress: String = "Start $start",
        endAddress: String = "End $start",
        distance: Double = 1_000.0,
    ) = RideExportItem(start, start + 60_000, 60_000, startAddress, endAddress, distance)

    private fun journey(
        start: Long,
        children: List<RideExportItem> = emptyList(),
        vehicle: String = "Test vehicle",
        startAddress: String = "Parent start $start",
        endAddress: String = "Parent end $start",
    ) = RideExportJourney(vehicle, start, start + 60_000, 60_000, startAddress, endAddress, 1_000.0, children)

    @Test
    fun standaloneAndMultipleRidesProduceOneRowEach() {
        val rows = parseCsv(buildRideCsv(listOf(journey(1), journey(2)), formatter))

        assertEquals(
            listOf("Type", "Parent", "Vehicle", "Date", "Start Time", "End Time", "Duration", "Start Address", "End Address", "Distance"),
            rows.first(),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf("Standalone", "Standalone"), rows.drop(1).map { it[0] })
        assertTrue(rows.drop(1).all { it[1].isEmpty() })
    }

    @Test
    fun combinedRideProducesParentAndChronologicalChildren() {
        val combined = journey(10, children = listOf(item(10), item(20), item(30)))
        val rows = parseCsv(buildRideCsv(listOf(combined), formatter))

        assertEquals(listOf("Combined", "Individual", "Individual", "Individual"), rows.drop(1).map { it[0] })
        assertEquals(listOf("Combined 1", "Combined 1", "Combined 1"), rows.drop(2).map { it[1] })
        assertEquals(listOf("date-10", "date-20", "date-30"), rows.drop(2).map { it[3] })
    }

    @Test
    fun mixedLogicalSelectionKeepsParentChildOrdering() {
        val rows =
            parseCsv(
                buildRideCsv(
                    listOf(journey(1), journey(2, listOf(item(2), item(3))), journey(4)),
                    formatter,
                ),
            )

        assertEquals(
            listOf("Standalone", "Combined", "Individual", "Individual", "Standalone"),
            rows.drop(1).map { it[0] },
        )
    }

    @Test
    fun csvEscapesCommasQuotesAndLineBreaksAndPreservesUtf8() {
        val csv =
            buildRideCsv(
                listOf(
                    journey(
                        start = 1,
                        vehicle = "VW \"Golf\", täglich",
                        startAddress = "Musterstraße 4, 31141 Hildesheim",
                        endAddress = "Zeile 1\nZeile \"2\", Größe",
                    ),
                ),
                formatter,
            )
        val row = parseCsv(csv)[1]

        assertEquals("VW \"Golf\", täglich", row[2])
        assertEquals("Musterstraße 4, 31141 Hildesheim", row[7])
        assertEquals("Zeile 1\nZeile \"2\", Größe", row[8])
        assertTrue(csv.contains("\"VW \"\"Golf\"\", täglich\""))
        assertTrue(csv.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8).contains("Musterstraße"))
    }

    @Test
    fun resolvedActualAddressesReachCsvWithoutSavedAliases() {
        val vehicle = Vehicle(1, "Daily", "VW", "Golf", "AB C 1", FuelType.PETROL, 10_000)
        val home = SavedAddress(10, "Home", SavedPlaceKind.HOME, 52.0, 9.0, 100, "home", "Leinkampstraße 25\n31141 Hildesheim")
        val office = SavedAddress(11, "Office", SavedPlaceKind.WORK, 52.1, 9.1, 100, "work", "Bahnhofstraße 1\n30159 Hannover")
        val ride =
            Ride(
                id = 1,
                vehicleId = 1,
                startedAtEpochMs = 1,
                startedElapsedNanos = 0,
                endedAtEpochMs = 60_001,
                distanceMeters = 1_000.0,
                sampleFile = "not_read.ndjson.gz",
                startAddress = "Home\n31141 Hildesheim",
                endAddress = "Office\n30159 Hannover",
                startAddressId = home.id,
                endAddressId = office.id,
            )
        val resolved =
            buildExportJourneys(
                listOf(RideExportRequest("r1", listOf(1))),
                listOf(ride),
                listOf(vehicle),
                listOf(home, office),
            )
        val csv = buildRideCsv(resolved, formatter)

        assertTrue(csv.contains("Leinkampstraße 25"))
        assertTrue(csv.contains("Bahnhofstraße 1"))
        assertFalse(parseCsv(csv)[1][7].startsWith("Home"))
        assertFalse(parseCsv(csv)[1][8].startsWith("Office"))
    }

    @Test
    fun csvFilenameDuplicatesAndMimeTypeAreCorrect() {
        val date = LocalDate.of(2026, 8, 22)
        val desired = exportFileName(date, RideExportFormat.CSV)

        assertEquals("RideSafe_Rides_Export_2026-08-22.csv", desired)
        assertEquals("RideSafe_Rides_Export_2026-08-22_2.csv", duplicateSafeFileName(desired, setOf(desired)))
        assertEquals("text/csv", RideExportFormat.CSV.mimeType)
        assertEquals("application/pdf", RideExportFormat.PDF.mimeType)
        assertEquals(
            "RideSafe_Rides_Export_2026-08-22_2.pdf",
            duplicateSafeFileName(exportFileName(date, RideExportFormat.PDF), setOf(exportFileName(date, RideExportFormat.PDF))),
        )
    }

    @Test
    fun successfulCsvPublishNotifiesWithFinalFilenameAndFormat() {
        val expected =
            CompletedRideExport(
                "RideSafe_Rides_Export_2026-08-22_2.csv",
                "content://downloads/42",
                RideExportFormat.CSV,
            )
        var notified: CompletedRideExport? = null

        val result = publishAndNotify(publish = { expected }, notify = { notified = it })

        assertEquals(expected, result)
        assertEquals(expected, notified)
    }

    @Test
    fun csvFormatFlowsThroughSuccessfulControllerState() =
        runBlocking {
            var receivedFormat: RideExportFormat? = null
            val completed = CompletedRideExport("export.csv", "content://downloads/2", RideExportFormat.CSV)
            val controller =
                RideExportController(
                    scope = this,
                    operation = { _, format ->
                        receivedFormat = format
                        completed
                    },
                )

            assertTrue(controller.start(listOf(RideExportRequest("r1", listOf(1))), RideExportFormat.CSV))
            yield()
            assertEquals(RideExportFormat.CSV, receivedFormat)
            assertEquals(RideExportState.Success(completed), controller.state.value)
        }
}

private fun parseCsv(csv: String): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < csv.length) {
        val character = csv[index]
        when {
            character == '"' && quoted && csv.getOrNull(index + 1) == '"' -> {
                field.append('"')
                index++
            }

            character == '"' -> {
                quoted = !quoted
            }

            character == ',' && !quoted -> {
                row += field.toString()
                field.clear()
            }

            character == '\n' && !quoted -> {
                row += field.toString().removeSuffix("\r")
                field.clear()
                rows += row
                row = mutableListOf()
            }

            else -> {
                field.append(character)
            }
        }
        index++
    }
    return rows.filterNot { it.size == 1 && it.single().isEmpty() }
}
