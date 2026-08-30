package de.uhi.enia.ridesafe.export

import android.net.Uri

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
