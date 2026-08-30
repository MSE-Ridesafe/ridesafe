@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.home

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.displayTitle
import de.uhi.enia.ridesafe.permissions.AppPermission
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.rides.recording.RecordingStatus
import de.uhi.enia.ridesafe.rides.recording.RideRecordingService
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

@Composable
fun HomeScreen(
    state: HomeDashboardState,
    onSelectVehicle: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    // The title doubles as the dashboard's vehicle filter once the garage offers a
                    // choice; with 0–1 cars there is nothing to select and the plain title stays.
                    if (state.vehicles.size >= 2) {
                        VehicleSelectorTitle(
                            vehicles = state.vehicles,
                            selectedVehicleId = state.selectedVehicleId,
                            onSelect = onSelectVehicle,
                        )
                    } else {
                        Text(
                            stringResource(R.string.screen_home_title),
                            style = MaterialTheme.typography.headlineMedium,
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
            // Extra room at the bottom so the last card can be scrolled out from under the FAB
            // (and, while a ride records, the floating recording bar that replaces it).
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val selectedVehicle = state.vehicles.firstOrNull { it.id == state.selectedVehicleId }
                when {
                    selectedVehicle != null -> VehicleCard(vehicle = selectedVehicle)
                    state.vehicles.size >= 2 -> GarageSummaryCard(vehicles = state.vehicles)
                    // 0–1 cars: observeAll() sorts primary first, so this is the old primary-or-first rule.
                    else -> VehicleCard(vehicle = state.vehicles.firstOrNull())
                }
            }
            item {
                SummaryMetricCarousel(
                    distanceMeters = state.totalDistanceMeters,
                    durationMillis = state.totalDurationMillis,
                    rideCount = state.totalRecordedRides,
                    monthDistanceMeters = state.currentMonthDistanceMeters,
                    monthDurationMillis = state.currentMonthDurationMillis,
                    monthRideCount = state.currentMonthRecordedRides,
                )
            }
            // The core feature (DSH-06) sits right under the mileage summary. Absent entirely until
            // a first ride has been scored — an empty gauge would only advertise a missing feature.
            if (state.safetyAllTime != null) {
                item {
                    SafetyScoreSection(
                        week = state.safetyWeek,
                        month = state.safetyMonth,
                        allTime = state.safetyAllTime,
                        scoreByWeek = state.safetyScoreByWeek,
                        scoreByMonth = state.safetyScoreByMonth,
                        scoreHistory = state.safetyScoreHistory,
                    )
                }
            }
            // Absent until the selection has a ratable ride, same rule as the safety card above it.
            if (state.ecoLevel != null) {
                item {
                    EcoSection(
                        level = state.ecoLevel,
                    )
                }
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
 * The screen title as the dashboard's scope: one car, or the whole garage. A tap opens the choice
 * as a plain [DropdownMenu] — deliberately not ExposedDropdownMenuBox, matching the logbook filter
 * sheet's precedent ([de.uhi.enia.ridesafe.ui.screens.rides]'s FilterDropdown).
 */
@Composable
private fun VehicleSelectorTitle(
    vehicles: List<Vehicle>,
    selectedVehicleId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = vehicles.firstOrNull { it.id == selectedVehicleId }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        onClickLabel = stringResource(R.string.home_vehicle_filter_cd),
                        role = Role.DropdownList,
                    ) { expanded = true }
                    .minimumInteractiveComponentSize(),
        ) {
            Text(
                text = selected?.displayTitle() ?: stringResource(R.string.rides_filter_vehicle_any),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false: short titles keep the arrow snug; long ones truncate, arrow visible.
                modifier = Modifier.weight(1f, fill = false),
            )
            MaterialSymbol(symbolName = "arrow_drop_down", contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VehicleMenuItem(
                label = stringResource(R.string.rides_filter_vehicle_any),
                selected = selected == null,
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            vehicles.forEach { vehicle ->
                VehicleMenuItem(
                    label = vehicle.displayTitle(),
                    selected = vehicle.id == selectedVehicleId,
                    onClick = {
                        expanded = false
                        onSelect(vehicle.id)
                    },
                )
            }
        }
    }
}

/** One garage entry; the check sits trailing so unselected labels stay aligned with the selected one. */
@Composable
private fun VehicleMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon =
            if (selected) {
                { MaterialSymbol(symbolName = "check", contentDescription = null, size = 18.dp) }
            } else {
                null
            },
        onClick = onClick,
    )
}

/**
 * Start a ride by hand (TRK-07) — for auto-tracking switched off, a car with no mapped device, or a
 * trigger that simply missed. Stopping belongs to the floating
 * [de.uhi.enia.ridesafe.ui.components.RecordingStatusBar], which is on screen in every tab while a
 * ride runs, so this button steps aside for it rather than offering the same thing twice.
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

    // Nothing to offer while a ride runs: the floating recording bar carries the clock and the
    // stop button, on this tab and every other one.
    AnimatedVisibility(
        visible = running == null,
        enter = scaleIn(),
        exit = scaleOut(),
    ) {
        ExtendedFloatingActionButton(
            onClick = {
                if (AppPermission.LOCATION.isGranted(context)) {
                    beginRide()
                } else {
                    requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            icon = { MaterialSymbol(symbolName = "play_arrow", contentDescription = null, fill = true) },
            text = { Text(stringResource(R.string.home_record_start)) },
        )
    }

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
