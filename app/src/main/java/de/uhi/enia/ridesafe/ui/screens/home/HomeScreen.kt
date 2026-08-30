@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.rides.recording.RideRecordingService
import de.uhi.enia.ridesafe.ui.components.EcoSection

@Composable
fun HomeScreen(
    state: HomeDashboardState,
    onSelectVehicle: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
        floatingActionButton = {
            RecordRideFab(
                vehicles = state.vehicles,
                onStartRide = { vehicleId ->
                    // Failure means the service refused the manual start (already recording, or
                    // location revoked between the FAB's check and here) — worth saying out loud.
                    if (!RideRecordingService.start(context, vehicleId, manual = true)) {
                        Toast.makeText(context, R.string.home_record_failed, Toast.LENGTH_LONG).show()
                    }
                },
            )
        },
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
