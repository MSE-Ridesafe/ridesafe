package de.uhi.enia.ridesafe.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import de.uhi.enia.ridesafe.core.location.haversineMeters
import java.util.Locale

/**
 * A user-saved address / "place" (entity DR-ADR). Anchored to an exact GPS point
 * ([latitude]/[longitude]) with a [radiusMeters] recognition area (ADR-02): a ride endpoint
 * inside that area is recognized as this place (ADR-07).
 *
 * [kind] drives singleton shortcuts (Home/Work/School/Gas station) with fixed labels and icons; a
 * [CUSTOM][SavedPlaceKind.CUSTOM] place has a user-chosen [label] and [icon] (a Material Symbols
 * ligature name). [address] is the reverse-geocoded (or searched) address at the point, kept so the
 * detail view can suppress the distance suffix when a ride endpoint's address matches it exactly
 * (ADR-09); null when geocoding was unavailable.
 */
@Entity(tableName = "saved_addresses")
data class SavedAddress(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val kind: SavedPlaceKind,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val icon: String,
    val address: String? = null,
)

/** Stored by [name]; user-facing labels are localized in the UI layer. */
enum class SavedPlaceKind {
    HOME,
    WORK,
    SCHOOL,
    GAS_STATION,
    CUSTOM,
}

/** The fixed Material Symbol for a shortcut kind, or null for [CUSTOM][SavedPlaceKind.CUSTOM] (user-chosen). */
fun SavedPlaceKind.fixedIcon(): String? =
    when (this) {
        SavedPlaceKind.HOME -> "home"
        SavedPlaceKind.WORK -> "work"
        SavedPlaceKind.SCHOOL -> "school"
        SavedPlaceKind.GAS_STATION -> "local_gas_station"
        SavedPlaceKind.CUSTOM -> null
    }

/** Home/Work/School keep their localized names; Gas station has a fixed icon but an editable name. */
val SavedPlaceKind.hasFixedLabel: Boolean
    get() = this == SavedPlaceKind.HOME || this == SavedPlaceKind.WORK || this == SavedPlaceKind.SCHOOL

/** Default icon for a fresh custom place. */
const val DEFAULT_PLACE_ICON = "place"

/**
 * The saved address a point falls into (ADR-07): the nearest by center distance among those whose
 * radius contains the point, or null when the point is unset or matches none. On overlap the nearest
 * center wins, so the result is deterministic.
 */
fun matchAddress(
    lat: Double?,
    lon: Double?,
    addresses: List<SavedAddress>,
): SavedAddress? {
    if (lat == null || lon == null) return null
    return addresses
        .mapNotNull { a ->
            val d = haversineMeters(lat, lon, a.latitude, a.longitude)
            if (d <= a.radiusMeters) a to d else null
        }.minByOrNull { it.second }
        ?.first
}

/**
 * Case- and punctuation-insensitive form used wherever two user-entered strings are compared for
 * identity — place labels and addresses, license plates, geocoder search results.
 */
fun normalizeForMatching(value: String): String = value.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)

/**
 * The already-saved place a picked search result duplicates (ADR-09): same normalized address, or
 * within 15 m of an existing center. [editedId] excludes the place being edited from its own check.
 */
fun findExistingSavedPlace(
    address: String,
    latitude: Double,
    longitude: Double,
    savedAddresses: List<SavedAddress>,
    editedId: Long?,
): SavedAddress? {
    val normalizedResult = normalizeForMatching(address)
    return savedAddresses
        .asSequence()
        .filterNot { it.id == editedId }
        .map { saved -> saved to haversineMeters(saved.latitude, saved.longitude, latitude, longitude) }
        .filter { (saved, distance) ->
            val sameAddress =
                saved.address
                    ?.let(::normalizeForMatching)
                    ?.takeIf(String::isNotEmpty) == normalizedResult
            sameAddress || distance <= 15.0
        }.minByOrNull { it.second }
        ?.first
}
