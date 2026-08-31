package de.uhi.enia.ridesafe.domain.place

import de.uhi.enia.ridesafe.core.location.haversineMeters
import de.uhi.enia.ridesafe.data.dao.RideDao
import de.uhi.enia.ridesafe.data.dao.SavedAddressDao
import de.uhi.enia.ridesafe.data.entity.SavedAddress
import de.uhi.enia.ridesafe.data.entity.SavedPlaceKind
import de.uhi.enia.ridesafe.data.entity.matchAddress
import de.uhi.enia.ridesafe.data.entity.normalizeForMatching

/**
 * Repairs duplicate places produced by older importers. Call inside a database transaction so a
 * ride can never observe its address removed before its reference has been repointed.
 */
suspend fun consolidateSavedAddressDuplicates(
    rideDao: RideDao,
    addressDao: SavedAddressDao,
) {
    val retained = mutableListOf<SavedAddress>()
    addressDao.all().sortedBy(SavedAddress::id).forEach { candidate ->
        val canonical = retained.firstOrNull { it.isEquivalentSavedPlace(candidate) }
        if (canonical == null) {
            retained += candidate
        } else {
            rideDao.replaceSavedAddressReferences(candidate.id, canonical.id)
            addressDao.deleteByIds(listOf(candidate.id))
        }
    }
}

private val singletonPlaceKinds =
    setOf(SavedPlaceKind.HOME, SavedPlaceKind.WORK, SavedPlaceKind.SCHOOL)

private fun SavedAddress.isEquivalentSavedPlace(other: SavedAddress): Boolean {
    if (kind != other.kind) return false
    if (kind in singletonPlaceKinds) return true
    if (normalizeForMatching(label) != normalizeForMatching(other.label)) return false

    val firstAddress = address?.let(::normalizeForMatching).orEmpty()
    val secondAddress = other.address?.let(::normalizeForMatching).orEmpty()
    val sameKnownAddress = firstAddress.isNotEmpty() && firstAddress == secondAddress
    val sameCoordinates = haversineMeters(latitude, longitude, other.latitude, other.longitude) <= 15.0
    return sameKnownAddress || sameCoordinates
}

/**
 * Re-match every ride's start/end point against the current saved addresses (ADR-07) and persist the
 * matched ids on the rides (the stored-match model). Run after any address add/edit/delete, and once
 * per launch to catch new recordings. Only changed rows are written.
 *
 * ponytail: O(rides × addresses) full scan on every address change — fine at personal scale; add a
 * spatial index or scope to affected rides if the ride count ever gets large.
 */
suspend fun rematchRides(
    rideDao: RideDao,
    addressDao: SavedAddressDao,
) {
    val addresses = addressDao.all()
    rideDao.all().forEach { ride ->
        val start = matchAddress(ride.startLat, ride.startLon, addresses)?.id
        val end = matchAddress(ride.endLat, ride.endLon, addresses)?.id
        if (start != ride.startAddressId || end != ride.endAddressId) {
            rideDao.setMatchedAddresses(ride.id, start, end)
        }
    }
}
