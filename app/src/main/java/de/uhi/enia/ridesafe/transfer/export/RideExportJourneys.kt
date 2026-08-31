package de.uhi.enia.ridesafe.transfer.export

import de.uhi.enia.ridesafe.data.entity.Ride
import de.uhi.enia.ridesafe.data.entity.SavedAddress
import de.uhi.enia.ridesafe.data.entity.Vehicle
import de.uhi.enia.ridesafe.data.entity.displayTitle
import de.uhi.enia.ridesafe.domain.ride.summarizeMerge

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

internal fun exportAddress(address: String?): String = address ?: "Unavailable"
