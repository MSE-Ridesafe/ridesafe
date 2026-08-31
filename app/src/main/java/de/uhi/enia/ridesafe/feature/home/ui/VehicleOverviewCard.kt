package de.uhi.enia.ridesafe.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.LicensePlateChip
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.core.format.currentUnitSystem
import de.uhi.enia.ridesafe.core.format.formatOdometer
import de.uhi.enia.ridesafe.data.entity.Vehicle
import de.uhi.enia.ridesafe.feature.garage.ui.VehicleImage

/**
 * The dashboard's header card: the car, its plate as the garage draws it, and the odometer as the
 * one headline figure — it moves with every refuel, unlike the rated consumption that used to sit
 * beside it and never changed.
 */
@Composable
fun VehicleCard(vehicle: Vehicle?) {
    val unitSystem = currentUnitSystem()
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VehicleImage(vehicle = vehicle, size = 86.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text =
                            vehicle
                                ?.let { it.name.trim().ifBlank { "${it.make} ${it.model}" } }
                                ?: stringResource(R.string.home_no_primary_vehicle),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = vehicle?.let { "${it.make} ${it.model}" } ?: stringResource(R.string.home_add_vehicle_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    vehicle?.licensePlate?.takeIf { it.isNotBlank() }?.let {
                        LicensePlateChip(plate = it)
                    }
                }
            }
            if (vehicle != null) {
                HeadlineMetric(
                    icon = "road",
                    label = stringResource(R.string.vehicle_mileage),
                    value = formatOdometer(vehicle.mileageKm, unitSystem),
                )
            }
        }
    }
}

/**
 * The header card's "All vehicles" face: the whole garage as one entry, with the fleet's combined
 * odometer as the same headline chip the single-car face carries.
 */
@Composable
fun GarageSummaryCard(vehicles: List<Vehicle>) {
    val unitSystem = currentUnitSystem()
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Same frame as VehicleImage's empty state, but with the Garage tab's glyph: this
                // entry is the whole garage, not one car. (VehicleImage itself is garage-internal.)
                Box(
                    modifier =
                        Modifier
                            .size(86.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    MaterialSymbol(
                        symbolName = "garage_home",
                        contentDescription = null,
                        color = MaterialTheme.colorScheme.onSurface,
                        size = 43.dp,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.rides_filter_vehicle_any),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.home_garage_vehicle_count, vehicles.size, vehicles.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HeadlineMetric(
                icon = "road",
                label = stringResource(R.string.home_garage_total_mileage),
                value = formatOdometer(vehicles.sumOf { it.mileageKm }, unitSystem),
            )
        }
    }
}

/**
 * The header card's one figure, drawn exactly like a carousel metric card's content — icon beside
 * the label, the value big beneath, no nested surface — so the dashboard's cards read as one
 * visual language.
 */
@Composable
private fun HeadlineMetric(
    icon: String,
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MaterialSymbol(
                symbolName = icon,
                contentDescription = null,
                color = MaterialTheme.colorScheme.primary,
                size = 24.dp,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = value,
            style =
                MaterialTheme.typography.displaySmall.copy(
                    fontSize = 30.sp,
                    lineHeight = 30.sp * 1.12f,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
