package de.uhi.enia.ridesafe.ui.screens.rides

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.summarizeMerge
import de.uhi.enia.ridesafe.permissions.AppPermission
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import de.uhi.enia.ridesafe.util.UnitPrefs
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDurationMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

private const val EXPORT_PREFIX = "ridesafe_export_"
private const val DOWNLOAD_FOLDER = "RideSafe"

// New id intentionally establishes LOW importance on devices that already created the earlier
// development channel at DEFAULT; Android channel importance is immutable after first creation.
private const val EXPORT_CHANNEL_ID = "ride_exports_complete"
private val mediaStoreRelativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER + "/"

/** Stable logical-entry snapshot. A merged request contains all of its physical stop ids. */
data class RideExportRequest(
    val key: String,
    val rideIds: List<Long>,
)

/** Resolved metadata for one physical ride nested under a combined journey. */
data class RideExportItem(
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val durationMs: Long?,
    val startAddress: String?,
    val endAddress: String?,
    val distanceMeters: Double?,
)

enum class RideExportFormat(
    val extension: String,
    val mimeType: String,
) {
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv"),
    ZIP("zip", "application/zip"),
}

/** Complete renderer input for one selected logical logbook entry. */
data class RideExportJourney(
    val vehicle: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val durationMs: Long?,
    val startAddress: String?,
    val endAddress: String?,
    val distanceMeters: Double?,
    val individualRides: List<RideExportItem> = emptyList(),
)

data class SavedRideExport(
    val fileName: String,
    val uri: Uri,
    val format: RideExportFormat = RideExportFormat.PDF,
)

/** Android-free completion value retained by the ViewModel/UI after MediaStore publishing. */
data class CompletedRideExport(
    val fileName: String,
    val contentUri: String,
    val format: RideExportFormat = RideExportFormat.PDF,
)

fun interface ExportCompletionNotifier {
    fun notify(saved: SavedRideExport)
}

sealed interface RideExportState {
    data object Idle : RideExportState

    data object Exporting : RideExportState

    data class Success(
        val export: CompletedRideExport,
    ) : RideExportState

    data object Error : RideExportState
}

/** Small lifecycle-aware operation guard/state holder, driven by the Rides ViewModel scope. */
class RideExportController(
    private val scope: CoroutineScope,
    private val operation: suspend (List<RideExportRequest>, RideExportFormat) -> CompletedRideExport,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val _state = MutableStateFlow<RideExportState>(RideExportState.Idle)
    val state: StateFlow<RideExportState> = _state.asStateFlow()

    fun start(
        requests: List<RideExportRequest>,
        format: RideExportFormat,
    ): Boolean {
        if (requests.isEmpty() || !_state.compareAndSet(RideExportState.Idle, RideExportState.Exporting)) return false
        scope.launch {
            try {
                _state.value = RideExportState.Success(operation(requests, format))
            } catch (cancelled: CancellationException) {
                _state.value = RideExportState.Idle
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
                _state.value = RideExportState.Error
            } finally {
                if (_state.value == RideExportState.Exporting) _state.value = RideExportState.Idle
            }
        }
        return true
    }

    fun consumeResult() {
        if (_state.value is RideExportState.Success || _state.value == RideExportState.Error) {
            _state.value = RideExportState.Idle
        }
    }
}

/** Snapshot selected logical entries in display order, deduplicating only within each entry. */
fun exportRequests(
    entries: List<LogbookEntry>,
    selectedKeys: Set<String>,
): List<RideExportRequest> {
    return entries.mapNotNull { entry ->
        if (entry.key !in selectedKeys) return@mapNotNull null
        val ids = entry.rideIds.distinct()
        ids.takeIf { it.isNotEmpty() }?.let { RideExportRequest(entry.key, it) }
    }
}

/** Orchestrates the one-shot automatic export without introducing a repository layer. */
class RideExporter(
    private val app: Application,
    private val notifier: ExportCompletionNotifier = AndroidExportCompletionNotifier(app),
) {
    private val db = RidesafeDatabase.getInstance(app)

    suspend fun export(
        requests: List<RideExportRequest>,
        format: RideExportFormat,
    ): CompletedRideExport =
        withContext(Dispatchers.IO) {
            require(requests.isNotEmpty())
            cleanupStaleTemps(app.cacheDir)
            val temp = File.createTempFile(EXPORT_PREFIX, ".${format.extension}", app.cacheDir)
            try {
                coroutineContext.ensureActive()
                val exportDate = LocalDate.now()
                when (format) {
                    RideExportFormat.PDF,
                    RideExportFormat.CSV,
                    -> {
                        val journeys = loadJourneys(requests)
                        require(journeys.isNotEmpty()) { "Selected rides no longer exist" }
                        val units = UnitPrefs.get(app)
                        when (format) {
                            RideExportFormat.PDF -> RidePdfReport(app).write(temp, journeys, exportDate, units)
                            RideExportFormat.CSV -> RideCsvReport(app).write(temp, journeys, units)
                            RideExportFormat.ZIP -> error("Handled outside this branch")
                        }
                    }

                    RideExportFormat.ZIP -> RideZipBackup(app, db).write(temp, requests)
                }
                coroutineContext.ensureActive()
                val saved =
                    publishAndNotify(
                        publish = { saveToDownloads(app, temp, exportDate, format) },
                        notify = notifier::notify,
                        onNotificationFailure = { Log.w("RideExport", "Could not post export notification", it) },
                    )
                CompletedRideExport(saved.fileName, saved.uri.toString(), saved.format)
            } finally {
                if (!temp.delete() && temp.exists()) temp.deleteOnExit()
            }
        }

    private suspend fun loadJourneys(requests: List<RideExportRequest>): List<RideExportJourney> {
        val allIds = requests.flatMap { it.rideIds }.distinct()
        val ridesById = db.rideDao().byIds(allIds).associateBy(Ride::id)
        val vehicleIds = ridesById.values.mapNotNull(Ride::vehicleId).distinct()
        val vehicles = if (vehicleIds.isEmpty()) emptyMap() else db.vehicleDao().byIds(vehicleIds).associateBy { it.id }
        val savedAddressIds =
            ridesById.values
                .flatMap { listOfNotNull(it.startAddressId, it.endAddressId) }
                .distinct()
        val savedAddresses =
            if (savedAddressIds.isEmpty()) {
                emptyMap()
            } else {
                db.savedAddressDao().byIds(savedAddressIds).associateBy(SavedAddress::id)
            }

        coroutineContext.ensureActive()
        return buildExportJourneys(
            requests,
            ridesById.values.toList(),
            vehicles.values.toList(),
            savedAddresses.values.toList(),
        )
    }
}

/** Pure persisted-data resolution; SQL result ordering is deliberately ignored and restored here. */
fun buildExportJourneys(
    requests: List<RideExportRequest>,
    rides: List<Ride>,
    vehicles: List<Vehicle>,
    savedAddresses: List<SavedAddress> = emptyList(),
): List<RideExportJourney> {
    val ridesById = rides.associateBy(Ride::id)
    val vehiclesById = vehicles.associateBy(Vehicle::id)
    val savedAddressesById = savedAddresses.associateBy(SavedAddress::id)
    return requests.mapNotNull { request ->
        val stops =
            request.rideIds
                .distinct()
                .mapNotNull(ridesById::get)
                .sortedBy(Ride::startedAtEpochMs)
        if (stops.isEmpty()) return@mapNotNull null
        val first = stops.first()
        val last = stops.last()
        val summary = if (stops.size > 1) summarizeMerge(stops) else null
        RideExportJourney(
            vehicle = first.vehicleId?.let(vehiclesById::get)?.displayTitle() ?: "Unknown vehicle",
            startedAtEpochMs = first.startedAtEpochMs,
            endedAtEpochMs = last.endedAtEpochMs,
            durationMs = summary?.movingDurationMs ?: first.endedAtEpochMs?.let { (it - first.startedAtEpochMs).coerceAtLeast(0) },
            startAddress = actualExportAddress(first.startAddress, first.startAddressId, savedAddressesById),
            endAddress = actualExportAddress(last.endAddress, last.endAddressId, savedAddressesById),
            distanceMeters = summary?.distanceMeters ?: first.distanceMeters,
            individualRides =
                if (request.key.startsWith("g")) {
                    stops.map { ride -> ride.toExportItem(savedAddressesById) }
                } else {
                    emptyList()
                },
        )
    }
}

private fun Ride.toExportItem(savedAddressesById: Map<Long, SavedAddress>): RideExportItem =
    RideExportItem(
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        durationMs = endedAtEpochMs?.let { (it - startedAtEpochMs).coerceAtLeast(0) },
        startAddress = actualExportAddress(startAddress, startAddressId, savedAddressesById),
        endAddress = actualExportAddress(endAddress, endAddressId, savedAddressesById),
        distanceMeters = distanceMeters,
    )

/**
 * Keeps the ride endpoint's own geocoded address unless it contains a matched saved-place label.
 * In that legacy case, use the saved place's geocoded address, never its user-facing custom name.
 */
internal fun actualExportAddress(
    persistedAddress: String?,
    savedAddressId: Long?,
    savedAddressesById: Map<Long, SavedAddress>,
): String? {
    val persisted = persistedAddress?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val saved = savedAddressId?.let(savedAddressesById::get) ?: return persisted
    val firstLine = persisted.lineSequence().firstOrNull()?.trim()
    if (!firstLine.equals(saved.label.trim(), ignoreCase = true)) return persisted

    return saved.address
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.takeUnless { actual ->
            actual
                .lineSequence()
                .firstOrNull()
                ?.trim()
                .equals(saved.label.trim(), ignoreCase = true)
        }
}

fun exportFileName(
    date: LocalDate,
    format: RideExportFormat = RideExportFormat.PDF,
): String = "RideSafe_Rides_Export_$date.${format.extension}"

fun duplicateSafeFileName(
    desired: String,
    existing: Set<String>,
): String {
    if (desired !in existing) return desired
    val extensionStart = desired.lastIndexOf('.').takeIf { it > 0 } ?: desired.length
    val stem = desired.substring(0, extensionStart)
    val extension = desired.substring(extensionStart)
    var suffix = 2
    while ("${stem}_$suffix$extension" in existing) suffix++
    return "${stem}_$suffix$extension"
}

private fun cleanupStaleTemps(cacheDir: File) {
    cacheDir
        .listFiles { file -> file.name.startsWith(EXPORT_PREFIX) }
        ?.forEach { it.delete() }
}

internal fun saveToDownloads(
    context: Context,
    source: File,
    date: LocalDate,
    format: RideExportFormat = RideExportFormat.PDF,
): SavedRideExport {
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val existing = mutableSetOf<String>()
    resolver
        .query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(mediaStoreRelativePath),
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) existing += cursor.getString(nameColumn)
        }
    val fileName = duplicateSafeFileName(exportFileName(date, format), existing)
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, mediaStoreRelativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
    try {
        resolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
            ?: error("MediaStore output stream unavailable")
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        check(resolver.update(uri, published, null, null) == 1) { "MediaStore publish failed" }
        return SavedRideExport(fileName, uri, format)
    } catch (cancelled: CancellationException) {
        resolver.delete(uri, null, null)
        throw cancelled
    } catch (failure: Exception) {
        resolver.delete(uri, null, null)
        throw failure
    }
}

internal fun <T> publishAndNotify(
    publish: () -> T,
    notify: (T) -> Unit,
    onNotificationFailure: (Throwable) -> Unit = {},
): T {
    val published = publish()
    runCatching { notify(published) }.onFailure(onNotificationFailure)
    return published
}

internal fun notificationsAllowed(
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): Boolean = permissionGranted && notificationsEnabled

internal fun exportAddress(address: String?): String = address ?: "Unavailable"

internal fun exportPendingIntentRequestCode(
    fileName: String,
    uriIdentity: String,
): Int = 31 * fileName.hashCode() + uriIdentity.hashCode()

internal fun buildOpenExportIntent(saved: SavedRideExport): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(saved.uri, saved.format.mimeType)
        identifier = saved.uri.toString()
        clipData = ClipData.newRawUri(saved.fileName, saved.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

private class AndroidExportCompletionNotifier(
    private val context: Context,
) : ExportCompletionNotifier {
    override fun notify(saved: SavedRideExport) {
        if (
            notificationsAllowed(
                permissionGranted = AppPermission.NOTIFICATIONS.isGranted(context),
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            )
        ) {
            post(saved)
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(saved: SavedRideExport) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                EXPORT_CHANNEL_ID,
                context.getString(R.string.ride_export_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openIntent =
            buildOpenExportIntent(saved)
                .takeIf { it.resolveActivity(context.packageManager) != null }
        val contentIntent =
            openIntent?.let {
                PendingIntent.getActivity(
                    context,
                    exportPendingIntentRequestCode(saved.fileName, saved.uri.toString()),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val builder =
            NotificationCompat
                .Builder(context, EXPORT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export)
                .setContentTitle(context.getString(R.string.ride_export_notification_title))
                .setContentText(context.getString(R.string.ride_export_notification_body, saved.fileName))
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(context.getString(R.string.ride_export_notification_body, saved.fileName)),
                ).setAutoCancel(true)
                .setContentIntent(contentIntent)
        if (contentIntent != null) {
            builder.addAction(
                R.drawable.ic_export,
                context.getString(R.string.ride_export_notification_open),
                contentIntent,
            )
        }
        val notification = builder.build()
        NotificationManagerCompat.from(context).notify(saved.fileName.hashCode(), notification)
    }
}

internal interface RideExportValueFormatter {
    fun date(epochMs: Long): String

    fun time(epochMs: Long): String

    fun duration(durationMs: Long?): String

    fun distance(distanceMeters: Double?): String
}

private class AndroidRideExportValueFormatter(
    context: Context,
    private val units: UnitSystemSetting,
) : RideExportValueFormatter {
    private val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, locale)

    override fun date(epochMs: Long): String = dateFormat.format(Date(epochMs))

    override fun time(epochMs: Long): String = timeFormat.format(Date(epochMs))

    override fun duration(durationMs: Long?): String = durationMs?.let(::formatDurationMs) ?: "Unavailable"

    override fun distance(distanceMeters: Double?): String = distanceMeters?.let { formatDistance(it, units) } ?: "Unavailable"
}

internal class RideCsvReport(
    private val context: Context,
) {
    fun write(
        file: File,
        journeys: List<RideExportJourney>,
        units: UnitSystemSetting,
    ) {
        val csv = buildRideCsv(journeys, AndroidRideExportValueFormatter(context, units))
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
    }
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
    return buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            if (character == '"') append('"')
            append(character)
        }
        append('"')
    }
}

internal class RidePdfReport(
    private val context: Context,
) {
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
            val formatter = AndroidRideExportValueFormatter(context, units)
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

    private fun locale(): Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

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
