package de.uhi.enia.ridesafe.tracking

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reverse-geocodes a coordinate to a display address via the native [Geocoder] (async API,
 * minSdk 34), built from the result's concrete fields (see [formatAddress]) and stored
 * newline-separated. Null when geocoding is unavailable, errors, times out, or returns nothing.
 * NOTE: the platform Geocoder calls a backend over the network, so this fails gracefully (null)
 * when offline — a deliberate networked exception, like the Maps route view.
 */
suspend fun reverseGeocode(
    context: Context,
    lat: Double,
    lon: Double,
): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context)
    return withTimeoutOrNull(10_000.milliseconds) {
        suspendCancellableCoroutine { cont ->
            try {
                geocoder.getFromLocation(
                    lat,
                    lon,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) {
                                cont.resume(runCatching { addresses.firstOrNull()?.let(::formatAddress) }.getOrNull())
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}

/**
 * Builds a two-line display address from an [Address]'s concrete fields (rather than parsing a
 * formatted line): primary = street + house number, or a named place / POI name when there's no
 * street; secondary = ZIP + city. Country and admin areas are omitted. The lines are joined by a
 * newline so [addressLines] can split them back exactly; null when nothing usable resolves.
 */
private fun formatAddress(a: Address): String? {
    val number = a.subThoroughfare
    val primary =
        when {
            a.featureName != null && a.featureName != number -> a.featureName

            // named place / POI
            a.thoroughfare != null -> listOfNotNull(a.thoroughfare, number).joinToString(" ")

            // "Hauptstraße 5"
            else -> a.featureName
        }?.trim()?.ifBlank { null }
    val secondary = listOfNotNull(a.postalCode, a.locality).joinToString(" ").trim().ifBlank { null }
    val lines = listOfNotNull(primary, secondary)
    return if (lines.isEmpty()) a.getAddressLine(0)?.trim()?.ifBlank { null } else lines.joinToString("\n")
}

/**
 * Splits a stored address into its (primary, secondary) display lines. Addresses are stored
 * newline-separated by [formatAddress] (primary = street/place, secondary = ZIP + city), so the
 * split is exact; secondary is null when there is only one line.
 */
fun addressLines(full: String): Pair<String, String?> {
    val parts = full.split("\n")
    return (parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: full) to parts.getOrNull(1)?.ifBlank { null }
}

/**
 * Short single-line display form (≈ "street, city"), e.g.
 * "Hauptstraße 5, 20095 Hamburg, Germany" -> "Hauptstraße 5, 20095 Hamburg".
 */
fun shortAddress(full: String): String = addressLines(full).let { (street, place) -> listOfNotNull(street, place).joinToString(", ") }
