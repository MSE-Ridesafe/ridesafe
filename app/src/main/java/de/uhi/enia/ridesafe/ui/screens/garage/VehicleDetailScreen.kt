@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                VehicleImage(size = 120.dp)
            }
            if (vehicle.isPrimary) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.garage_primary)) },
                    leadingIcon = {
                        MaterialSymbol(symbolName = "star", contentDescription = null, fill = true)
                    },
                    colors =
                        AssistChipDefaults.assistChipColors(
                            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.primary,
                        ),
                )
            }

            Field(R.string.vehicle_make, vehicle.make)
            Field(R.string.vehicle_model, vehicle.model)
            Field(R.string.vehicle_year, vehicle.year?.toString() ?: notSet)
            Field(R.string.vehicle_license_plate, vehicle.licensePlate)
            Field(R.string.vehicle_fuel_type, stringResource(vehicle.fuelType.labelRes()))
            Field(
                R.string.vehicle_mileage,
                formatOdometer(context, vehicle.mileageKm, unitSystem),
            )
            Field(
                R.string.vehicle_fuel_economy,
                vehicle.fuelEconomy?.let { "$it ${stringResource(R.string.unit_fuel_economy)}" } ?: notSet,
            )
            Field(
                R.string.vehicle_tank_size,
                vehicle.tankSize?.let { "$it ${stringResource(R.string.unit_liter)}" } ?: notSet,
            )
        }
    }
}

@Composable
private fun Field(
    labelRes: Int,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
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
