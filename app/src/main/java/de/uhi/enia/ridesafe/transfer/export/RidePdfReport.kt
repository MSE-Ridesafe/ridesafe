package de.uhi.enia.ridesafe.transfer.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import de.uhi.enia.ridesafe.core.format.UnitSystemSetting
import de.uhi.enia.ridesafe.core.format.formattingLocale
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

internal class RidePdfReport {
    private val body =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 39, 42)
            textSize = 11f
        }
    private val label = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val title =
        Paint(label).apply {
            textSize = 22f
            color = Color.rgb(27, 85, 95)
        }
    private val section =
        Paint(label).apply {
            textSize = 16f
            color = Color.rgb(27, 85, 95)
        }
    private val subsection =
        Paint(label).apply {
            textSize = 13f
            color = Color.rgb(27, 85, 95)
        }
    private val nestedSection = Paint(label).apply { textSize = 12f }
    private val muted = Paint(body).apply { color = Color.rgb(95, 99, 104) }
    private val rule =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 211, 213)
            strokeWidth = 1f
        }

    fun write(
        file: File,
        journeys: List<RideExportJourney>,
        exportDate: LocalDate,
        units: UnitSystemSetting,
    ) {
        val document = PdfDocument()
        try {
            val writer = PageWriter(document)
            val formatter = AndroidRideExportValueFormatter(units)
            writer.text("RideSafe Rides Export", title, after = 8f)
            writer.text("Exported: ${localizedDate(exportDate)}", muted, after = 18f)
            journeys.forEachIndexed { index, journey ->
                writer.ensure(96f)
                writer.rule()
                val heading = if (journey.individualRides.isEmpty()) "Ride ${index + 1}" else "Combined Ride ${index + 1}"
                writer.text(heading, section, before = 12f, after = 10f)
                writer.field("Vehicle", journey.vehicle)
                writer.field("Date", formatter.date(journey.startedAtEpochMs))
                writer.field("Start time", formatter.time(journey.startedAtEpochMs))
                writer.field("End time", journey.endedAtEpochMs?.let(formatter::time) ?: "Unavailable")
                writer.field("Duration", formatter.duration(journey.durationMs))
                writer.field("Start", exportAddress(journey.startAddress))
                writer.field("End", exportAddress(journey.endAddress))
                writer.field("Distance", formatter.distance(journey.distanceMeters))
                if (journey.individualRides.isNotEmpty()) {
                    writer.ensure(112f)
                    writer.text("Individual rides", subsection, before = 10f, after = 8f, indent = 12f)
                    journey.individualRides.forEachIndexed { childIndex, ride ->
                        writer.ensure(72f)
                        writer.text("Ride ${childIndex + 1}", nestedSection, before = 6f, after = 7f, indent = 24f)
                        writer.field("Date", formatter.date(ride.startedAtEpochMs), indent = 24f)
                        writer.field("Start time", formatter.time(ride.startedAtEpochMs), indent = 24f)
                        writer.field(
                            "End time",
                            ride.endedAtEpochMs?.let(formatter::time) ?: "Unavailable",
                            indent = 24f,
                        )
                        writer.field("Duration", formatter.duration(ride.durationMs), indent = 24f)
                        writer.field("Start", exportAddress(ride.startAddress), indent = 24f)
                        writer.field("End", exportAddress(ride.endAddress), indent = 24f)
                        writer.field(
                            "Distance",
                            formatter.distance(ride.distanceMeters),
                            indent = 24f,
                        )
                        writer.gap(8f)
                    }
                }
                writer.gap(16f)
            }
            writer.finish()
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun localizedDate(date: LocalDate): String =
        localizedDate(Date.from(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))

    private fun localizedDate(date: Date): String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale()).format(date)

    private fun locale(): Locale = formattingLocale()

    private inner class PageWriter(
        private val document: PdfDocument,
    ) {
        private val pageWidth = 595
        private val pageHeight = 842
        private val margin = 48f
        private val bottom = pageHeight - margin
        private val contentWidth = pageWidth - margin * 2
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = margin

        init {
            newPage()
        }

        fun ensure(height: Float) {
            if (y + height > bottom) newPage()
        }

        fun gap(height: Float) {
            ensure(height)
            y += height
        }

        fun rule() {
            ensure(2f)
            canvas!!.drawLine(margin, y, pageWidth - margin, y, rule)
            y += 2f
        }

        fun text(
            value: String,
            paint: Paint,
            before: Float = 0f,
            after: Float = 0f,
            indent: Float = 0f,
        ) {
            gap(before)
            wrap(value, paint, indent).forEach { line ->
                ensure(lineHeight(paint))
                y += -paint.fontMetrics.ascent
                canvas!!.drawText(line, margin + indent, y, paint)
                y += paint.fontMetrics.descent + 2f
            }
            gap(after)
        }

        fun field(
            name: String,
            value: String,
            indent: Float = 0f,
        ) {
            ensure(lineHeight(label) + lineHeight(body) + 9f)
            text(name, label, after = 2f, indent = indent)
            text(value, body, after = 7f, indent = indent)
        }

        fun finish() {
            page?.let(document::finishPage)
            page = null
            canvas = null
        }

        private fun newPage() {
            page?.let(document::finishPage)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page!!.canvas
            y = margin
        }

        private fun lineHeight(paint: Paint): Float = paint.fontMetrics.descent - paint.fontMetrics.ascent + 2f

        private fun wrap(
            value: String,
            paint: Paint,
            indent: Float,
        ): List<String> {
            if (value.isEmpty()) return listOf("—")
            val result = mutableListOf<String>()
            value.lines().forEach { paragraph ->
                var remaining = paragraph.trim()
                if (remaining.isEmpty()) {
                    result += ""
                }
                while (remaining.isNotEmpty()) {
                    var count = paint.breakText(remaining, true, contentWidth - indent, null).coerceAtLeast(1)
                    if (count < remaining.length) {
                        val boundary = remaining.lastIndexOf(' ', count - 1)
                        if (boundary > 0) count = boundary
                    }
                    result += remaining.substring(0, count).trimEnd()
                    remaining = remaining.substring(count).trimStart()
                }
            }
            return result
        }
    }
}
