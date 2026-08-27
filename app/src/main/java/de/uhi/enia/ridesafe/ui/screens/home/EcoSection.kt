package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.RideEco
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.rides.processing.score.ecoLevel
import de.uhi.enia.ridesafe.ui.components.EcoLevelDisplay
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle

/**
 * The dashboard's aggregated eco level (ANL-03): every profiled ride pooled into one 0–3 reading,
 * filterable by garage vehicle — the same segmented-bar language as the ride detail's card, so the
 * two are recognisably one measure.
 *
 * The filter mirrors the safety section's window chips in look but selects a *car*, not a period:
 * efficiency is where vehicles genuinely differ (commute car vs weekend van), while the safety
 * score describes the driver across all of them. Chips only appear once the garage has a second
 * vehicle — with one car the pool and the car are the same reading.
 *
 * A selection with nothing profiled keeps the card up and says so, rather than flickering the card
 * away when a chip is tapped — same rule as the safety card's empty state.
 */
@Composable
fun EcoSection(
    allVehicles: RideEco?,
    byVehicle: Map<Long, RideEco>,
    vehicles: List<Vehicle>,
) {
    // null = all vehicles. A selected car deleted from the garage falls back to the pool.
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedId = selected.takeIf { id -> vehicles.any { it.id == id } }
    val profile = selectedId?.let(byVehicle::get) ?: if (selectedId == null) allVehicles else null
    val level = profile?.let { ecoLevel(it) }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ride_detail_section_eco),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                MaterialSymbol(
                    symbolName = "eco",
                    contentDescription = null,
                    size = 20.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (vehicles.size >= 2) {
                VehicleChips(
                    vehicles = vehicles,
                    selectedId = selectedId,
                    onSelect = { selected = it },
                )
            }

            if (level != null) {
                EcoLevelDisplay(level = level)
            } else {
                Text(
                    text = stringResource(R.string.home_eco_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "All vehicles" plus one chip per garage car; scrolls sideways once the garage outgrows the row. */
@Composable
private fun VehicleChips(
    vehicles: List<Vehicle>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        EcoVehicleChip(
            label = stringResource(R.string.rides_filter_vehicle_any),
            selected = selectedId == null,
            onClick = { onSelect(null) },
        )
        vehicles.forEach { vehicle ->
            EcoVehicleChip(
                label = vehicle.displayTitle(),
                selected = selectedId == vehicle.id,
                onClick = { onSelect(vehicle.id) },
            )
        }
    }
}

/** One filter chip, styled like the safety section's window chips so the controls read as kin. */
@Composable
private fun EcoVehicleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        label = { Text(label) },
        leadingIcon =
            if (selected) {
                {
                    MaterialSymbol(
                        symbolName = "check",
                        contentDescription = null,
                        size = 18.dp,
                    )
                }
            } else {
                null
            },
    )
}
