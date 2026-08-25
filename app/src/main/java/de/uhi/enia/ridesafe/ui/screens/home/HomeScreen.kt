@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.home

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.permissions.AppPermission
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.rides.recording.RecordingStatus
import de.uhi.enia.ridesafe.rides.recording.RideRecordingService
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle

@Composable
fun HomeScreen(
    state: HomeDashboardState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.screen_home_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = {
                    IconButton(onClick = { }) {
                        MaterialSymbol(
                            symbolName = "notifications",
                            contentDescription = stringResource(R.string.home_notifications),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = { RecordRideFab(vehicles = state.vehicles) },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            // Extra room at the bottom so the last card can be scrolled out from under the FAB.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                VehicleCard(
                    vehicle = state.primaryVehicle,
                )
            }
            state.activeRide?.let { activeRide ->
                item {
                    ActiveRideCard(activeRide = activeRide)
                }
            }
            item {
                SummaryMetricCarousel(
                    distanceMeters = state.totalDistanceMeters,
                    durationMillis = state.totalDurationMillis,
                    rideCount = state.totalRecordedRides,
                    fuelLiters = state.totalFuelLiters,
                    monthDistanceMeters = state.currentMonthDistanceMeters,
                    monthDurationMillis = state.currentMonthDurationMillis,
                    monthRideCount = state.currentMonthRecordedRides,
                    monthFuelLiters = state.currentMonthFuelLiters,
                )
            }
            item {
                HighlightsCard(
                    highlights = state.highlights,
                )
            }
            item {
                ActivitySection(
                    activityByDay = state.activityByDay,
                )
            }
        }
    }
}

/**
 * Start or stop a ride by hand (TRK-07) — for auto-tracking switched off, a car with no mapped
 * device, or a trigger that simply missed. Recording state comes from [RecordingStatus], the
 * engine's own live flag, rather than from the dashboard flow: the running ride's database row is
 * incomplete until it is finalized, which is exactly why the logbook ignores it.
 */
@Composable
private fun RecordRideFab(vehicles: List<Vehicle>) {
    val context = LocalContext.current
    val running by RecordingStatus.running.collectAsState()
    var picking by remember { mutableStateOf(false) }

    fun startRide(vehicleId: Long?) {
        if (!RideRecordingService.start(context, vehicleId, manual = true)) {
            Toast.makeText(context, R.string.home_record_failed, Toast.LENGTH_LONG).show()
        }
    }

    // Which car is this ride on (TRK-08)? Only worth asking when the garage leaves a choice — and
    // it has to be asked up front, since a ride's vehicle can't be changed afterwards.
    fun beginRide() {
        if (vehicles.size > 1) picking = true else startRide(vehicles.firstOrNull()?.id)
    }

    // A ride needs GPS, so this is where location gets requested (NFR-05): Settings only demands it
    // once automatic tracking is on, and manual recording works with automatic tracking off.
    val requestLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            PermissionState.refresh(context)
            if (granted) beginRide()
        }

    ExtendedFloatingActionButton(
        onClick = {
            when {
                running != null -> RideRecordingService.stop(context, manual = true)
                AppPermission.LOCATION.isGranted(context) -> beginRide()
                else -> requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        },
        // Ending a ride is the weightier half, so it carries the colour — same reasoning as the car screen.
        containerColor =
            if (running != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        icon = {
            MaterialSymbol(
                symbolName = if (running != null) "stop" else "play_arrow",
                contentDescription = null,
                fill = true,
            )
        },
        text = { Text(stringResource(if (running != null) R.string.home_record_stop else R.string.home_record_start)) },
    )

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.car_pick_vehicle)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    vehicles.forEach { vehicle ->
                        ListItem(
                            headlineContent = { Text(vehicle.displayTitle()) },
                            supportingContent =
                                vehicle.licensePlate
                                    .takeIf { it.isNotBlank() }
                                    ?.let { { Text(it) } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier =
                                Modifier.clickable {
                                    picking = false
                                    startRide(vehicle.id)
                                },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
