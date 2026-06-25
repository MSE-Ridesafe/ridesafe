@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.tracking.LocationSample
import de.uhi.enia.ridesafe.tracking.trackDistanceMeters
import de.uhi.enia.ridesafe.ui.components.DetailCard
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatRideDateTime
import de.uhi.enia.ridesafe.util.formatSpeed

/**
 * Ride detail: the recorded route drawn on a Google Map, plus summary/speed/distance cards.
 * [track] is null while the sample file is still loading; empty when the ride recorded no GPS.
 * Distance and average speed are computed live from [track] (the analysis pass ANL-02 doesn't
 * run yet, so [Ride.distanceMeters]/[Ride.avgSpeedMps] are still null on disk).
 */
@Composable
fun RideDetailScreen(
    ride: Ride?,
    track: List<LocationSample>?,
    unitSystem: UnitSystemSetting,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(ride?.let { formatRideDateTime(context, it.startedAtEpochMs) } ?: "") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        MaterialSymbol(
                            symbolName = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        // ride is null only briefly while the Flow loads, or if it was removed.
        if (ride == null) return@Scaffold

        val distanceMeters = track?.let { trackDistanceMeters(it) }
        val durationSec = ride.endedAtEpochMs?.let { (it - ride.startedAtEpochMs) / 1000.0 }
        val avgMps =
            if (distanceMeters != null && durationSec != null && durationSec > 0) distanceMeters / durationSec else null

        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RouteMapCard(track = track)

            DetailCard(
                title = stringResource(R.string.ride_detail_section_summary),
                rows =
                    listOfNotNull(
                        ride.startAddress?.let { stringResource(R.string.ride_detail_from) to it },
                        ride.endAddress?.let { stringResource(R.string.ride_detail_to) to it },
                        stringResource(R.string.ride_detail_start) to formatRideDateTime(context, ride.startedAtEpochMs),
                        ride.endedAtEpochMs?.let {
                            stringResource(R.string.ride_detail_end) to formatRideDateTime(context, it)
                        },
                        formatDuration(ride.startedAtEpochMs, ride.endedAtEpochMs)?.let {
                            stringResource(R.string.ride_detail_duration) to it
                        },
                    ),
            )

            DetailCard(
                title = stringResource(R.string.ride_detail_section_speed),
                rows =
                    listOfNotNull(
                        stringResource(R.string.ride_detail_max_speed) to formatSpeed(context, ride.maxSpeedMps, unitSystem),
                        avgMps?.let {
                            stringResource(R.string.ride_detail_avg_speed) to formatSpeed(context, it, unitSystem)
                        },
                    ),
            )

            DetailCard(
                title = stringResource(R.string.ride_detail_section_distance),
                rows =
                    listOf(
                        stringResource(R.string.ride_detail_total_distance) to
                            (
                                distanceMeters?.let { formatDistance(context, it, unitSystem) }
                                    ?: stringResource(R.string.value_not_set)
                            ),
                    ),
            )
        }
    }
}

@Composable
private fun RouteMapCard(track: List<LocationSample>?) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp),
    ) {
        when {
            track == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            track.isEmpty() -> {
                NoGps()
            }

            else -> {
                RouteMap(track)
            }
        }
    }
}

@Composable
private fun RouteMap(track: List<LocationSample>) {
    val points = remember(track) { track.map { LatLng(it.lat, it.lon) } }
    val cameraPositionState =
        rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(points.first(), 14f) }
    var mapLoaded by remember { mutableStateOf(false) }
    val routeColor = MaterialTheme.colorScheme.primary

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        // Lite mode: a static route snapshot — lighter and fewer tile fetches than a live map.
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
        onMapLoaded = { mapLoaded = true },
    ) {
        Polyline(points = points, color = routeColor, width = 12f)
        Marker(state = rememberMarkerState(position = points.first()), title = stringResource(R.string.ride_start_marker))
        Marker(state = rememberMarkerState(position = points.last()), title = stringResource(R.string.ride_end_marker))
    }

    // Frame the whole route once the (lite-mode) map has a laid-out size.
    LaunchedEffect(mapLoaded, points) {
        if (mapLoaded && points.size > 1) {
            val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
            runCatching { cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 100)) }
        }
    }
}

@Composable
private fun NoGps() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MaterialSymbol(
            symbolName = "location_off",
            contentDescription = null,
            size = 40.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.ride_detail_no_gps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
