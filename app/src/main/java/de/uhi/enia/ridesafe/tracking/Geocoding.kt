package de.uhi.enia.ridesafe.tracking

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reverse-geocodes a coordinate to a full formatted address line via the native [Geocoder]
 * (async API, minSdk 34), or null when geocoding is unavailable, errors, times out, or returns
 * nothing. NOTE: the platform Geocoder calls a backend over the network, so this fails gracefully
 * (null) when offline — a deliberate networked exception, like the Maps route view.
 */
suspend fun reverseGeocode(
    context: Context,
    lat: Double,
    lon: Double,
): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context)
    return withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            try {
                geocoder.getFromLocation(
                    lat,
                    lon,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull()?.getAddressLine(0))
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}

/**
 * Short display form of a full address line: the first up-to-two comma-separated parts
 * (≈ street, city), e.g. "Hauptstraße 5, 20095 Hamburg, Germany" -> "Hauptstraße 5, 20095 Hamburg".
 */
fun shortAddress(full: String): String =
    full
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString(", ")
