package de.uhi.enia.ridesafe.transfer.export

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

/** A ZIP export reads every selected ride three times: to hash it, to archive it, to verify it. */
private const val PASSES_PER_RIDE = 3

/**
 * How far an export has got, for the Logbook's progress dialog.
 *
 * [passes] counts ride-passes rather than rides because the three passes cannot be interleaved —
 * the archive has to exist before it can be read back — so a per-ride counter would run 1..n three
 * times over. Counting passes keeps [ridesDone] monotonic and [fraction] close to linear in wall
 * time. PDF and CSV exports never set [rides]; they read no sample files and finish immediately, so
 * their fraction stays 0 and the dialog shows an indeterminate ring.
 */
data class RideExportProgress(
    val passes: Int = 0,
    val rides: Int = 0,
) {
    val fraction: Float
        get() = if (rides == 0) 0f else (passes.toFloat() / (rides * PASSES_PER_RIDE)).coerceIn(0f, 1f)

    val ridesDone: Int get() = (passes / PASSES_PER_RIDE).coerceAtMost(rides)
}

/** Android-free completion value retained by the ViewModel/UI after MediaStore publishing. */
data class CompletedRideExport(
    val fileName: String,
    val contentUri: String,
    val format: RideExportFormat = RideExportFormat.PDF,
)
