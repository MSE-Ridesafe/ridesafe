@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatOdometer

@Composable
fun VehicleDetailScreen(
    vehicle: Vehicle?,
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
                title = { Text(vehicle?.name ?: "") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
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
        // vehicle is null only briefly while the Flow loads, or if it was removed.
        if (vehicle == null) return@Scaffold

        val notSet = stringResource(R.string.value_not_set)
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VehicleHeader(vehicle)

            OdometerCard(value = formatOdometer(context, vehicle.mileageKm, unitSystem))

            DetailCard(
                titleRes = R.string.vehicle_section_overview,
                rows =
                    listOf(
                        stringResource(R.string.vehicle_make) to vehicle.make,
                        stringResource(R.string.vehicle_model) to vehicle.model,
                        stringResource(R.string.vehicle_year) to (vehicle.year?.toString() ?: notSet),
                        stringResource(R.string.vehicle_license_plate) to vehicle.licensePlate,
                    ),
            )

            DetailCard(
                titleRes = R.string.vehicle_section_fuel,
                rows =
                    listOf(
                        stringResource(R.string.vehicle_fuel_type) to stringResource(vehicle.fuelType.labelRes()),
                        stringResource(R.string.vehicle_fuel_economy) to
                            (vehicle.fuelEconomy?.let { "$it ${stringResource(R.string.unit_fuel_economy)}" } ?: notSet),
                        stringResource(R.string.vehicle_tank_size) to
                            (vehicle.tankSize?.let { "$it ${stringResource(R.string.unit_liter)}" } ?: notSet),
                    ),
            )
        }
    }
}

/** Hero identity block: title image, make · model, and the primary badge. */
@Composable
private fun VehicleHeader(vehicle: Vehicle) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VehicleImage(size = 120.dp)
        Text(
            text = "${vehicle.make} ${vehicle.model}",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vehicle.isPrimary) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.garage_primary)) },
                leadingIcon = {
                    MaterialSymbol(symbolName = "favorite", contentDescription = null, fill = true)
                },
                colors =
                    AssistChipDefaults.assistChipColors(
                        disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                        disabledLeadingIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
            )
        }
    }
}

/** Highlighted hero stat — the odometer is the vehicle's most-watched number. */
@Composable
private fun OdometerCard(value: String) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaterialSymbol(
                symbolName = "speed",
                contentDescription = null,
                size = 32.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.vehicle_mileage),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** A titled group of label/value rows, divider-separated, like a spec sheet. */
@Composable
private fun DetailCard(
    @StringRes titleRes: Int,
    rows: List<Pair<String, String>>,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                }
                DetailRow(label = label, value = value)
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Preview
@Composable
private fun VehicleDetailPreview() {
    RidesafeTheme {
        VehicleDetailScreen(
            vehicle = previewVehicles.first(),
            unitSystem = UnitSystemSetting.METRIC,
            onBack = {},
        )
    }
}
