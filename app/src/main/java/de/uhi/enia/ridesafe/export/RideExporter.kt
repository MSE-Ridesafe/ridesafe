package de.uhi.enia.ridesafe.export

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.backup.RideZipBackup
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.permissions.AppPermission
import de.uhi.enia.ridesafe.util.UnitPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private const val EXPORT_PREFIX = "ridesafe_export_"
private const val DOWNLOAD_FOLDER = "ridesafe"

// New id intentionally establishes LOW importance on devices that already created the earlier
// development channel at DEFAULT; Android channel importance is immutable after first creation.
private const val EXPORT_CHANNEL_ID = "ride_exports_complete"
private val mediaStoreRelativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER + "/"

/** Orchestrates the one-shot automatic export without introducing a repository layer. */
class RideExporter(
    private val app: Application,
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
                val exportDate = LocalDate.now()
                when (format) {
                    RideExportFormat.PDF -> RidePdfReport().write(temp, loadJourneys(requests), exportDate, UnitPrefs.get(app))
                    RideExportFormat.CSV -> writeRideCsv(temp, loadJourneys(requests), UnitPrefs.get(app))
                    RideExportFormat.ZIP -> RideZipBackup(app, db).write(temp, requests)
                }
                coroutineContext.ensureActive()
                val saved = saveToDownloads(app, temp, exportDate, format)
                // A failed notification must never fail a successful publish.
                runCatching { notifyExportComplete(app, saved) }
                    .onFailure { Log.w("RideExport", "Could not post export notification", it) }
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

        return buildExportJourneys(
            requests,
            ridesById.values.toList(),
            vehicles.values.toList(),
            savedAddresses.values.toList(),
        ).also { require(it.isNotEmpty()) { "Selected rides no longer exist" } }
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

internal fun notificationsAllowed(
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): Boolean = permissionGranted && notificationsEnabled

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

@SuppressLint("MissingPermission")
private fun notifyExportComplete(
    context: Context,
    saved: SavedRideExport,
) {
    if (
        !notificationsAllowed(
            permissionGranted = AppPermission.NOTIFICATIONS.isGranted(context),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    ) {
        return
    }
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

/** Whether any installed app can display [saved]; decides the snackbar's "Open" action. */
fun canOpenExportedFile(
    context: Context,
    saved: SavedRideExport,
): Boolean = buildOpenExportIntent(saved).resolveActivity(context.packageManager) != null

/** Open a completed export in its viewer; failures are logged, never thrown at the UI. */
fun openExportedFile(
    context: Context,
    saved: SavedRideExport,
) {
    runCatching { context.startActivity(buildOpenExportIntent(saved)) }
        .onFailure { Log.w("RideExport", "Could not open exported file", it) }
}
