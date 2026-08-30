package de.uhi.enia.ridesafe.ui.screens.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import de.uhi.enia.ridesafe.rides.processing.AddressSearchResult
import de.uhi.enia.ridesafe.rides.processing.forwardGeocodeSuggestions
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.util.SEARCH_SUGGESTION_LIMIT
import de.uhi.enia.ridesafe.util.haversineMeters
import de.uhi.enia.ridesafe.util.loadRecentAddressSearches
import de.uhi.enia.ridesafe.util.recordRecentAddressSearch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

internal const val SEARCH_DEBOUNCE_MS = 400L
internal const val SEARCH_MIN_LENGTH = 3

/** The address search box's state and moves — see [rememberAddressSearch]. */
internal class AddressSearchState(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val active: Boolean,
    val setActive: (Boolean) -> Unit,
    val results: List<AddressSearchResult>,
    val loading: Boolean,
    val completed: Boolean,
    val recents: List<String>,
    val choose: (AddressSearchResult) -> Unit,
    val recordQuery: () -> Unit,
)

/**
 * The forward-geocode search machinery (ADR-05): a debounced suggestion query sorted by distance
 * to [near], plus the persisted recent-search history. Choosing a result collapses the search and
 * hands the pick to [onResultChosen]; focus/keyboard belong to the caller.
 */
@Composable
internal fun rememberAddressSearch(
    near: LatLng?,
    onResultChosen: (AddressSearchResult) -> Unit,
): AddressSearchState {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var active by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<AddressSearchResult>()) }
    var loading by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(loadRecentAddressSearches(context)) }

    LaunchedEffect(query, active) {
        val trimmed = query.trim()
        if (!active || trimmed.length < SEARCH_MIN_LENGTH) {
            results = emptyList()
            loading = false
            completed = false
            return@LaunchedEffect
        }
        loading = true
        completed = false
        delay(SEARCH_DEBOUNCE_MS.milliseconds)
        results =
            forwardGeocodeSuggestions(context, trimmed, limit = SEARCH_SUGGESTION_LIMIT)
                .sortedBy { result ->
                    near?.let {
                        haversineMeters(
                            it.latitude,
                            it.longitude,
                            result.latitude,
                            result.longitude,
                        )
                    }
                        ?: Double.MAX_VALUE
                }
        loading = false
        completed = true
    }

    return AddressSearchState(
        query = query,
        onQueryChange = { query = it },
        active = active,
        setActive = { active = it },
        results = results,
        loading = loading,
        completed = completed,
        recents = recents,
        choose = { result ->
            query = shortAddress(result.address)
            active = false
            recents = recordRecentAddressSearch(context, shortAddress(result.address))
            onResultChosen(result)
        },
        recordQuery = {
            if (query.trim().length >= SEARCH_MIN_LENGTH) {
                recents = recordRecentAddressSearch(context, query)
            }
        },
    )
}

/**
 * The "use my location" flow (NFR-05): permission request when needed, then a one-shot
 * high-accuracy fix. Returns the trigger; outcomes land in [onLocated]/[onFailed]. A denied
 * request counts as a failure so the screen can say why nothing happened.
 */
@SuppressLint("MissingPermission") // launched only after the runtime check/grant below
@Composable
internal fun rememberLocationRequester(
    onLocated: (LatLng) -> Unit,
    onFailed: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val located by rememberUpdatedState(onLocated)
    val failed by rememberUpdatedState(onFailed)

    fun locate() {
        val cts = CancellationTokenSource()
        fused
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) located(LatLng(loc.latitude, loc.longitude)) else failed()
            }.addOnFailureListener { failed() }
    }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) locate() else failed()
        }

    return {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) locate() else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
