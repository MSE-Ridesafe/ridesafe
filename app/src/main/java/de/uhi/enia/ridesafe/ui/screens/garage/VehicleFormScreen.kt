@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.displayTitle
import de.uhi.enia.ridesafe.ui.components.ConfirmDestructiveDialog
import de.uhi.enia.ridesafe.ui.components.DestructiveOutlinedButton
import de.uhi.enia.ridesafe.ui.components.FormScaffold
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.NumberField
import de.uhi.enia.ridesafe.ui.components.SectionTitle
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.usesMetric
import kotlin.math.roundToInt

private const val KM_PER_MILE = 1.609344

/**
 * Add/edit form for a vehicle. [existing] null = add mode (GAR-02); non-null = edit mode
 * (GAR-03), with fields pre-filled and a delete action wired through [onDelete] (GAR-04).
 * [embedded] renders the form chromeless for a host that brings its own chrome (the
 * onboarding): no app bar — so no title, cancel, or duplicate back affordance — and the save
 * action pinned full-width at the bottom instead; [onBack] goes unused there.
 */
@Composable
fun VehicleFormScreen(
    existing: Vehicle?,
    onSave: (vehicle: Vehicle, makePrimary: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    embedded: Boolean = false,
) {
    val unitSystem = currentUnitSystem()
    val metric = usesMetric(unitSystem)
    val editing = existing != null

    // Odometer is stored in km; show/edit it in the user's display unit.
    val initialMileage =
        existing?.let { if (metric) it.mileageKm else (it.mileageKm / KM_PER_MILE).roundToInt() }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var make by rememberSaveable { mutableStateOf(existing?.make ?: "") }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: "") }
    var licensePlate by rememberSaveable { mutableStateOf(existing?.licensePlate ?: "") }
    var year by rememberSaveable { mutableStateOf(existing?.year?.toString() ?: "") }
    var mileage by rememberSaveable { mutableStateOf(initialMileage?.toString() ?: "") }
    var fuelEconomy by rememberSaveable { mutableStateOf(existing?.fuelEconomy?.toString() ?: "") }
    var tankSize by rememberSaveable { mutableStateOf(existing?.tankSize?.toString() ?: "") }
    var vehicleType by rememberSaveable { mutableStateOf(existing?.vehicleType.orEmpty()) }
    var engine by rememberSaveable { mutableStateOf(existing?.engine.orEmpty()) }
    var manufacturingCountry by rememberSaveable { mutableStateOf(existing?.manufacturingCountry.orEmpty()) }
    var fuelType by rememberSaveable { mutableStateOf(existing?.fuelType ?: FuelType.UNSPECIFIED) }
    var makePrimary by rememberSaveable { mutableStateOf(existing?.isPrimary ?: false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showExtendedInformation by rememberSaveable { mutableStateOf(false) }

    val mileageValue = mileage.toIntOrNull()
    val canSave =
        make.isNotBlank() &&
            model.isNotBlank() &&
            licensePlate.isNotBlank() &&
            mileageValue != null &&
            mileageValue >= 0

    fun save() {
        val mileageKm = if (metric) mileageValue!! else (mileageValue!! * KM_PER_MILE).roundToInt()
        val edited =
            (existing ?: Vehicle(name = "", make = "", model = "", licensePlate = "", fuelType = fuelType, mileageKm = 0))
                .copy(
                    name = name.trim(),
                    make = make.trim(),
                    model = model.trim(),
                    licensePlate = licensePlate.trim(),
                    fuelType = fuelType,
                    mileageKm = mileageKm,
                    year = year.toIntOrNull(),
                    fuelEconomy = fuelEconomy.toDoubleOrNull(),
                    tankSize = tankSize.toDoubleOrNull(),
                    vehicleType = vehicleType.trim().ifBlank { null },
                    engine = engine.trim().ifBlank { null },
                    manufacturingCountry = manufacturingCountry.trim().ifBlank { null },
                )
        onSave(edited, makePrimary)
    }

    FormScaffold(
        title = stringResource(if (editing) R.string.garage_edit_vehicle else R.string.garage_add_vehicle),
        canSave = canSave,
        onSave = ::save,
        onBack = onBack,
        modifier = modifier,
        embedded = embedded,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = {
                Text(stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_nickname)))
            },
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

        NumberField(
            value = mileage,
            onValueChange = { mileage = it.filter(Char::isDigit) },
            label = stringResource(R.string.vehicle_mileage),
            suffix = stringResource(if (metric) R.string.unit_km else R.string.unit_mi),
            keyboardType = KeyboardType.Number,
        )
        NumberField(
            value = year,
            onValueChange = { year = it.filter(Char::isDigit) },
            label = stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_year)),
            keyboardType = KeyboardType.Number,
        )

        OutlinedButton(
            onClick = { showExtendedInformation = !showExtendedInformation },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.vehicle_extended_information),
                modifier = Modifier.weight(1f),
            )
            MaterialSymbol(
                symbolName = if (showExtendedInformation) "expand_less" else "expand_more",
                contentDescription = null,
                size = 22.dp,
            )
        }

        if (showExtendedInformation) {
            SectionTitle(text = stringResource(R.string.vehicle_section_fuel), modifier = Modifier.padding(top = 4.dp))
            FuelTypeDropdown(selected = fuelType, onSelected = { fuelType = it })
            NumberField(
                value = fuelEconomy,
                onValueChange = { fuelEconomy = it },
                label = stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_fuel_economy)),
                suffix = stringResource(R.string.unit_fuel_economy),
            )
            NumberField(
                value = tankSize,
                onValueChange = { tankSize = it },
                label = stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_tank_size)),
                suffix = stringResource(R.string.unit_liter),
            )

            SectionTitle(text = stringResource(R.string.vehicle_section_information), modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                value = vehicleType,
                onValueChange = { vehicleType = it },
                label = {
                    Text(stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_type)))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = engine,
                onValueChange = { engine = it },
                label = {
                    Text(stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_engine)))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = manufacturingCountry,
                onValueChange = { manufacturingCountry = it },
                label = {
                    Text(
                        stringResource(
                            R.string.vehicle_label_optional,
                            stringResource(R.string.vehicle_manufacturing_country),
                        ),
                    )
                },
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
                // Can't demote the current primary directly (GAR-07) — promote another instead.
                Switch(
                    checked = makePrimary,
                    onCheckedChange = { makePrimary = it },
                    enabled = existing?.isPrimary != true,
                )
            }
        }

        if (onDelete != null) {
            DestructiveOutlinedButton(
                label = stringResource(R.string.garage_delete_vehicle),
                onClick = { showDeleteDialog = true },
            )
        }
    }

    if (showDeleteDialog && onDelete != null) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.garage_delete_confirm_title),
            message = stringResource(R.string.garage_delete_confirm_message, existing?.displayTitle().orEmpty()),
            onConfirm = onDelete,
            onDismiss = { showDeleteDialog = false },
        )
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
            label = {
                Text(stringResource(R.string.vehicle_label_optional, stringResource(R.string.vehicle_fuel_type)))
            },
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


