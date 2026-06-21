@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.usesMetric
import kotlin.math.roundToInt

@Composable
fun AddVehicleScreen(
    unitSystem: UnitSystemSetting,
    onSave: (vehicle: Vehicle, makePrimary: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val metric = usesMetric(context, unitSystem)

    var name by rememberSaveable { mutableStateOf("") }
    var make by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var licensePlate by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var mileage by rememberSaveable { mutableStateOf("") }
    var fuelEconomy by rememberSaveable { mutableStateOf("") }
    var tankSize by rememberSaveable { mutableStateOf("") }
    var fuelType by rememberSaveable { mutableStateOf(FuelType.PETROL) }
    var makePrimary by rememberSaveable { mutableStateOf(false) }

    val mileageValue = mileage.toIntOrNull()
    val canSave =
        name.isNotBlank() &&
            make.isNotBlank() &&
            model.isNotBlank() &&
            licensePlate.isNotBlank() &&
            mileageValue != null &&
            mileageValue >= 0

    fun save() {
        val mileageKm = if (metric) mileageValue!! else (mileageValue!! * 1.609344).roundToInt()
        onSave(
            Vehicle(
                name = name.trim(),
                make = make.trim(),
                model = model.trim(),
                licensePlate = licensePlate.trim(),
                fuelType = fuelType,
                mileageKm = mileageKm,
                year = year.toIntOrNull(),
                fuelEconomy = fuelEconomy.toDoubleOrNull(),
                tankSize = tankSize.toDoubleOrNull(),
            ),
            makePrimary,
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.garage_add_vehicle)) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        MaterialSymbol(
                            symbolName = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = canSave) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.vehicle_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = make,
                onValueChange = { make = it },
                label = { Text(stringResource(R.string.vehicle_make)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.vehicle_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = licensePlate,
                onValueChange = { licensePlate = it },
                label = { Text(stringResource(R.string.vehicle_license_plate)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FuelTypeDropdown(selected = fuelType, onSelected = { fuelType = it })

            OutlinedTextField(
                value = mileage,
                onValueChange = { mileage = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.vehicle_mileage)) },
                suffix = { Text(stringResource(if (metric) R.string.unit_km else R.string.unit_mi)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = year,
                onValueChange = { year = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_year))) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = fuelEconomy,
                onValueChange = { fuelEconomy = it },
                label = {
                    Text(stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_fuel_economy)))
                },
                suffix = { Text(stringResource(R.string.unit_fuel_economy)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tankSize,
                onValueChange = { tankSize = it },
                label = {
                    Text(stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_tank_size)))
                },
                suffix = { Text(stringResource(R.string.unit_liter)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.add_vehicle_set_primary),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = makePrimary, onCheckedChange = { makePrimary = it })
            }
        }
    }
}

@Composable
private fun FuelTypeDropdown(
    selected: FuelType,
    onSelected: (FuelType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(selected.labelRes()),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.vehicle_fuel_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FuelType.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Preview
@Composable
private fun AddVehiclePreview() {
    RidesafeTheme {
        AddVehicleScreen(unitSystem = UnitSystemSetting.METRIC, onSave = { _, _ -> }, onBack = {})
    }
}
