@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDayHeader
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatSpeed
import de.uhi.enia.ridesafe.util.formatTimeOfDay
import de.uhi.enia.ridesafe.util.rideDay
import java.time.LocalDate

@Composable
fun RidesScreen(
    rides: List<RideRow>,
    unitSystem: UnitSystemSetting,
    onRideClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.screen_rides_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        if (rides.isEmpty()) {
            EmptyRides(
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(32.dp),
            )
            return@Scaffold
        }

        // Group into one card per calendar day; rides arrive newest-first, so insertion order
        // gives newest day first, newest ride first within each day.
        val groups = remember(rides) { rides.groupByTo(LinkedHashMap()) { rideDay(it.ride.startedAtEpochMs) } }
        val today = LocalDate.now()

        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { (day, dayRides) ->
                item(key = "h$day") {
                    DayHeader(text = formatDayHeader(context, day, today))
                }
                item(key = "c$day") {
                    Card(
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            dayRides.forEachIndexed { index, row ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                                }
                                RideListItem(
                                    row = row,
                                    unitSystem = unitSystem,
                                    onClick = { onRideClick(row.ride.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun RideListItem(
    row: RideRow,
    unitSystem: UnitSystemSetting,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val ride = row.ride
    val timeRange =
        buildString {
            append(formatTimeOfDay(context, ride.startedAtEpochMs))
            ride.endedAtEpochMs?.let {
                append(" – ")
                append(formatTimeOfDay(context, it))
            }
        }
    val supporting =
        listOfNotNull(
            formatDuration(ride.startedAtEpochMs, ride.endedAtEpochMs),
            stringResource(R.string.ride_max_speed_short, formatSpeed(context, ride.maxSpeedMps, unitSystem)),
        ).joinToString("  •  ")

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { MaterialSymbol(symbolName = "route", contentDescription = null) },
        overlineContent = row.vehicleName?.let { name -> { Text(name) } },
        headlineContent = { Text(timeRange) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            MaterialSymbol(
                symbolName = "chevron_right",
                contentDescription = stringResource(R.string.ride_open),
            )
        },
    )
}

@Composable
private fun EmptyRides(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MaterialSymbol(
            symbolName = "route",
            contentDescription = null,
            size = 64.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.rides_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.rides_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
