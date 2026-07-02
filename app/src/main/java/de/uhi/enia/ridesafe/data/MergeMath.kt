package de.uhi.enia.ridesafe.data

/**
 * Pure merge/un-merge logic (no Android deps) for ride merging (§3.8). Kept separate from the DB and
 * UI so the mathematically-valid aggregation (MRG-05) and the merge/un-merge rules (MRG-02, MRG-09,
 * MRG-11) are unit-testable in isolation. See MergeMathTest.
 *
 * Why a set of selected rides can or cannot be merged (MRG-08 shows the reason to the user). */
enum class MergeCheck {
    OK,
    NOT_ENOUGH, // fewer than two rides selected
    MIXED_VEHICLE, // different vehicles, or an unassigned ride (MRG-09)
    NOT_CONTIGUOUS, // a same-vehicle ride between the selected ones is left out (MRG-02)
}

/**
 * Aggregated metrics for a merged ride, recomputed from its stops (MRG-05). Distance/duration are
 * summed over the stops (never spanning the parked gaps between them, MRG-07); average speed is total
 * distance over total moving duration (not a mean of per-stop speeds); top speed is the max across
 * stops. Endpoints come straight from the first stop's start and the last stop's end.
 */
data class MergedSummary(
    val stopCount: Int,
    val distanceMeters: Double?,
    val movingDurationMs: Long,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double,
    val startEpochMs: Long,
    val endEpochMs: Long?,
    val startAddress: String?,
    val endAddress: String?,
)

/** Summarize a merged ride from its stops (any order; sorted here by start time). */
fun summarizeMerge(stops: List<Ride>): MergedSummary {
    require(stops.isNotEmpty()) { "a merged ride has at least one stop" }
    val ordered = stops.sortedBy { it.startedAtEpochMs }
    val first = ordered.first()
    val last = ordered.last()

    // Sum only distances/durations we actually have; a stop still being processed contributes 0
    // rather than poisoning the whole total (MRG-05 is best-effort until every stop is processed).
    val distances = ordered.mapNotNull { it.distanceMeters }
    val totalDistance = if (distances.isEmpty()) null else distances.sum()
    val movingDurationMs =
        ordered.sumOf { s -> s.endedAtEpochMs?.let { it - s.startedAtEpochMs } ?: 0L }
    val movingSec = movingDurationMs / 1000.0
    val avgSpeed = if (totalDistance != null && movingSec > 0) totalDistance / movingSec else null

    return MergedSummary(
        stopCount = ordered.size,
        distanceMeters = totalDistance,
        movingDurationMs = movingDurationMs,
        avgSpeedMps = avgSpeed,
        maxSpeedMps = ordered.maxOf { it.maxSpeedMps },
        startEpochMs = first.startedAtEpochMs,
        endEpochMs = last.endedAtEpochMs,
        startAddress = first.startAddress,
        endAddress = last.endAddress,
    )
}

/**
 * Whether the [selectedIds] can be merged, given every ride in the logbook ([allRides]). Merging is
 * allowed only for two or more rides of the same assigned vehicle (MRG-09) that are contiguous among
 * that vehicle's rides — other vehicles' rides interleaved in time don't count and don't block it
 * (MRG-02). An unassigned ride (null vehicle) is never mergeable.
 */
fun canMerge(
    selectedIds: Set<Long>,
    allRides: List<Ride>,
): MergeCheck {
    val selected = allRides.filter { it.id in selectedIds }
    if (selected.size < 2) return MergeCheck.NOT_ENOUGH

    val vehicle = selected.first().vehicleId
    if (vehicle == null || selected.any { it.vehicleId != vehicle }) return MergeCheck.MIXED_VEHICLE

    // Contiguity is checked only among this vehicle's rides: the selected ones must form an unbroken
    // run (no unselected same-vehicle ride sitting between the earliest and latest selected).
    val vehicleRides = allRides.filter { it.vehicleId == vehicle }.sortedBy { it.startedAtEpochMs }
    val indices = vehicleRides.indices.filter { vehicleRides[it].id in selectedIds }
    return if (indices.last() - indices.first() == indices.size - 1) MergeCheck.OK else MergeCheck.NOT_CONTIGUOUS
}

/**
 * The merge-group id to assign when merging [rideIds]: the smallest id involved, so an existing
 * merged group (already tagged with its smallest id) keeps its id when a later ride is appended, and
 * merging two groups unifies them under the smaller (MRG-10).
 */
fun mergeGroupIdFor(rideIds: Collection<Long>): Long = rideIds.min()

/**
 * Un-merge rules (MRG-11): stops may only be peeled off the start or end of the sequence, so whatever
 * stays merged must remain a contiguous middle block. A selection of stop indices (into the ordered
 * stops, size [n]) is a valid peel iff the *unselected* stops form one contiguous range (or none are
 * left, which is equivalent to "unmerge all").
 */
fun isValidPeel(
    selected: Set<Int>,
    n: Int,
): Boolean {
    val remaining = (0 until n).filterNot { it in selected }
    if (remaining.isEmpty()) return true
    return remaining.last() - remaining.first() == remaining.size - 1
}

/** Whether tapping stop [index] keeps the peel valid — drives which stop checkboxes are enabled (MRG-11). */
fun canToggleStop(
    index: Int,
    selected: Set<Int>,
    n: Int,
): Boolean = if (index in selected) true else isValidPeel(selected + index, n)

/** Whether the current stop selection can be un-merged: at least one stop, and a valid end-peel (MRG-11). */
fun canUnmergeSelection(
    selected: Set<Int>,
    n: Int,
): Boolean = selected.isNotEmpty() && isValidPeel(selected, n)
