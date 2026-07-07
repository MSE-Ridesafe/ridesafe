@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting

@Composable
fun HomeScreen(
    state: HomeDashboardState,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystemSetting = UnitSystemSetting.AUTOMATIC,
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
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                VehicleCard(
                    vehicle = state.primaryVehicle,
                    unitSystem = unitSystem,
                )
            }
            state.activeRide?.let { activeRide ->
                item {
                    ActiveRideCard(activeRide = activeRide)
                }
            }
            item {
                MonthlyStats(
                    distanceMeters = state.totalDistanceMeters,
                    durationMillis = state.totalDurationMillis,
                    unitSystem = unitSystem,
                )
            }
            item {
                HighlightsCard(
                    highlights = state.highlights,
                    unitSystem = unitSystem,
                )
            }
            item {
                ActivitySection(
                    activityByDay = state.activityByDay,
                    unitSystem = unitSystem,
                )
            }
        }
    }
}
