package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.data.MergeCheck
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.canMerge
import kotlin.math.abs

enum class RefuelAssociationCheck {
    OK,
    WRONG_SELECTION,
    VEHICLE_MISMATCH,
    OTHER_JOURNEY,
    NO_CHANGES,
    NOT_ALL_ATTACHED,
}

/**
 * The two-part merge verdict for a mixed selection: which side blocks it, so the selection bar can
 * name the right reason (MRG-08). The combined gate lives on [LogbookAction.enabled].
 */
data class MixedMergeCheck(
    val rideCheck: MergeCheck,
    val refuelCheck: RefuelAssociationCheck = RefuelAssociationCheck.OK,
)

fun checkAddRefuelsToRide(
    selectedRides: List<LogbookEntry>,
    selectedRefuels: List<Refuel>,
    allRides: List<Ride>,
): RefuelAssociationCheck {
    if (selectedRides.size != 1 || selectedRefuels.isEmpty()) return RefuelAssociationCheck.WRONG_SELECTION
    val targetRides = selectedRides.single().rides
    val targetIds = targetRides.mapTo(hashSetOf()) { it.id }
    val vehicleId = targetRides.firstOrNull()?.vehicleId
    if (vehicleId == null || targetRides.any { it.vehicleId != vehicleId } || selectedRefuels.any { it.vehicleId != vehicleId }) {
        return RefuelAssociationCheck.VEHICLE_MISMATCH
    }
    val liveRideIds = allRides.mapTo(hashSetOf()) { it.id }
    if (selectedRefuels.any { it.journeyAnchorRideId in liveRideIds && it.journeyAnchorRideId !in targetIds }) {
        return RefuelAssociationCheck.OTHER_JOURNEY
    }
    if (selectedRefuels.all { it.journeyAnchorRideId in targetIds }) return RefuelAssociationCheck.NO_CHANGES
    return RefuelAssociationCheck.OK
}

fun checkRemoveRefuelsFromRide(
    selectedRides: List<LogbookEntry>,
    selectedRefuels: List<Refuel>,
    allRides: List<Ride>,
): RefuelAssociationCheck {
    if (selectedRides.isNotEmpty() || selectedRefuels.isEmpty()) return RefuelAssociationCheck.WRONG_SELECTION
    val liveRideIds = allRides.mapTo(hashSetOf()) { it.id }
    return if (selectedRefuels.all { it.journeyAnchorRideId in liveRideIds }) {
        RefuelAssociationCheck.OK
    } else {
        RefuelAssociationCheck.NOT_ALL_ATTACHED
    }
}

fun checkMixedMerge(
    selectedRides: List<LogbookEntry>,
    selectedRefuels: List<Refuel>,
    allRides: List<Ride>,
): MixedMergeCheck {
    val rideIds = selectedRides.flatMapTo(hashSetOf()) { it.rideIds }
    val rideCheck = canMerge(rideIds, allRides)
    if (rideCheck != MergeCheck.OK || selectedRefuels.isEmpty()) return MixedMergeCheck(rideCheck)
    val selectedPhysicalRides = allRides.filter { it.id in rideIds }
    val vehicleId = selectedPhysicalRides.first().vehicleId
    if (selectedRefuels.any { it.vehicleId != vehicleId }) {
        return MixedMergeCheck(rideCheck, RefuelAssociationCheck.VEHICLE_MISMATCH)
    }
    val liveRideIds = allRides.mapTo(hashSetOf()) { it.id }
    if (selectedRefuels.any { it.journeyAnchorRideId in liveRideIds && it.journeyAnchorRideId !in rideIds }) {
        return MixedMergeCheck(rideCheck, RefuelAssociationCheck.OTHER_JOURNEY)
    }
    return MixedMergeCheck(rideCheck)
}

/** What the selection's primary action does — the Logbook bar offers exactly one of these. */
enum class LogbookActionKind { MERGE, UNMERGE, ATTACH, DETACH }

/**
 * The primary action for the current selection, plus why it is disabled (MRG-08). Attaching a Refuel
 * to a journey is not a merge: it only anchors the Refuel to a ride, so a *single* ride is a valid
 * target and no merge group is created. Refuels without a ride have nothing to anchor to, so that
 * selection can only ever be a detach.
 */
data class LogbookAction(
    val kind: LogbookActionKind,
    val rideCheck: MergeCheck = MergeCheck.OK,
    val refuelCheck: RefuelAssociationCheck = RefuelAssociationCheck.OK,
    val unmergeGroupId: Long? = null,
) {
    val enabled get() = rideCheck == MergeCheck.OK && refuelCheck == RefuelAssociationCheck.OK
}

fun logbookAction(
    selectedRides: List<LogbookEntry>,
    selectedRefuels: List<Refuel>,
    allRides: List<Ride>,
): LogbookAction {
    val merged = selectedRides.singleOrNull() as? LogbookEntry.Merged
    return when {
        selectedRefuels.isEmpty() && merged != null ->
            LogbookAction(LogbookActionKind.UNMERGE, unmergeGroupId = merged.groupId)

        // Rides only, or several journeys plus Refuels: merging, with the Refuels carried along.
        selectedRefuels.isEmpty() || selectedRides.size > 1 ->
            checkMixedMerge(selectedRides, selectedRefuels, allRides).let {
                LogbookAction(LogbookActionKind.MERGE, it.rideCheck, it.refuelCheck)
            }

        selectedRides.isEmpty() ->
            LogbookAction(
                LogbookActionKind.DETACH,
                refuelCheck = checkRemoveRefuelsFromRide(selectedRides, selectedRefuels, allRides),
            )

        else ->
            LogbookAction(
                LogbookActionKind.ATTACH,
                refuelCheck = checkAddRefuelsToRide(selectedRides, selectedRefuels, allRides),
            )
    }
}

/** Closest start; exact distance ties prefer the earlier start and then lower persistent id. */
fun closestRideAnchor(
    refuel: Refuel,
    rides: List<Ride>,
): Ride =
    rides.minWith(
        compareBy<Ride> { abs(it.startedAtEpochMs - refuel.timestampEpochMs) }
            .thenBy { it.startedAtEpochMs }
            .thenBy { it.id },
    )

sealed interface CombinedJourneyChild {
    val sortEpochMs: Long
    val persistentId: Long

    data class RideChild(
        val ride: Ride,
    ) : CombinedJourneyChild {
        override val sortEpochMs get() = ride.startedAtEpochMs
        override val persistentId get() = ride.id
    }

    data class RefuelChild(
        val row: RefuelRow,
    ) : CombinedJourneyChild {
        override val sortEpochMs get() = row.refuel.timestampEpochMs
        override val persistentId get() = row.refuel.id
    }
}

val combinedJourneyChildComparator =
    compareBy<CombinedJourneyChild> { it.sortEpochMs }
        .thenBy { if (it is CombinedJourneyChild.RideChild) 0 else 1 }
        .thenBy { it.persistentId }

fun combinedJourneyChildren(
    rides: List<Ride>,
    refuels: List<RefuelRow>,
): List<CombinedJourneyChild> =
    (rides.map(CombinedJourneyChild::RideChild) + refuels.map(CombinedJourneyChild::RefuelChild))
        .sortedWith(combinedJourneyChildComparator)
