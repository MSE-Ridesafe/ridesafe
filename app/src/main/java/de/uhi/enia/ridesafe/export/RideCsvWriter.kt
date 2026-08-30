package de.uhi.enia.ridesafe.export

import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDurationMs
import de.uhi.enia.ridesafe.util.formattingLocale
import java.io.File
import java.text.DateFormat
import java.util.Date

internal interface RideExportValueFormatter {
    fun date(epochMs: Long): String

    fun time(epochMs: Long): String

    fun duration(durationMs: Long?): String

    fun distance(distanceMeters: Double?): String
}

internal class AndroidRideExportValueFormatter(
    private val units: UnitSystemSetting,
) : RideExportValueFormatter {
    private val locale = formattingLocale()
    private val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, locale)

    override fun date(epochMs: Long): String = dateFormat.format(Date(epochMs))

    override fun time(epochMs: Long): String = timeFormat.format(Date(epochMs))

    override fun duration(durationMs: Long?): String = durationMs?.let(::formatDurationMs) ?: "Unavailable"

    override fun distance(distanceMeters: Double?): String = distanceMeters?.let { formatDistance(it, units) } ?: "Unavailable"
}

internal fun writeRideCsv(
    file: File,
    journeys: List<RideExportJourney>,
    units: UnitSystemSetting,
) {
    val csv = buildRideCsv(journeys, AndroidRideExportValueFormatter(units))
    file.outputStream().bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
}

private val csvHeader =
    listOf("Type", "Parent", "Vehicle", "Date", "Start Time", "End Time", "Duration", "Start Address", "End Address", "Distance")

internal fun buildRideCsv(
    journeys: List<RideExportJourney>,
    formatter: RideExportValueFormatter,
): String =
    buildString {
        appendCsvRecord(csvHeader)
        var combinedIndex = 0
        journeys.forEach { journey ->
            if (journey.individualRides.isEmpty()) {
                appendCsvRecord(csvValues("Standalone", "", journey.vehicle, journey, formatter))
            } else {
                combinedIndex++
                val parent = "Combined $combinedIndex"
                appendCsvRecord(csvValues("Combined", "", journey.vehicle, journey, formatter))
                journey.individualRides.forEach { ride ->
                    appendCsvRecord(
                        listOf(
                            "Individual",
                            parent,
                            journey.vehicle,
                            formatter.date(ride.startedAtEpochMs),
                            formatter.time(ride.startedAtEpochMs),
                            ride.endedAtEpochMs?.let(formatter::time) ?: "Unavailable",
                            formatter.duration(ride.durationMs),
                            exportAddress(ride.startAddress),
                            exportAddress(ride.endAddress),
                            formatter.distance(ride.distanceMeters),
                        ),
                    )
                }
            }
        }
    }

private fun csvValues(
    type: String,
    parent: String,
    vehicle: String,
    journey: RideExportJourney,
    formatter: RideExportValueFormatter,
): List<String> =
    listOf(
        type,
        parent,
        vehicle,
        formatter.date(journey.startedAtEpochMs),
        formatter.time(journey.startedAtEpochMs),
        journey.endedAtEpochMs?.let(formatter::time) ?: "Unavailable",
        formatter.duration(journey.durationMs),
        exportAddress(journey.startAddress),
        exportAddress(journey.endAddress),
        formatter.distance(journey.distanceMeters),
    )

private fun StringBuilder.appendCsvRecord(values: List<String>) {
    append(values.joinToString(",", transform = ::escapeCsvField))
    append("\r\n")
}

internal fun escapeCsvField(value: String): String {
    if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}
