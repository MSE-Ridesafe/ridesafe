@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.refuel.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.FormScaffold
import de.uhi.enia.ridesafe.core.components.NumberField
import de.uhi.enia.ridesafe.core.format.currentUnitSystem
import de.uhi.enia.ridesafe.core.format.formattingLocale
import de.uhi.enia.ridesafe.core.format.usesMetric
import de.uhi.enia.ridesafe.core.preferences.currentCurrencySetting
import de.uhi.enia.ridesafe.data.entity.Refuel
import de.uhi.enia.ridesafe.data.entity.Vehicle
import de.uhi.enia.ridesafe.domain.refuel.currencyUnitsToMinor
import de.uhi.enia.ridesafe.domain.refuel.litersToMilliliters
import de.uhi.enia.ridesafe.domain.refuel.odometerToMeters
import de.uhi.enia.ridesafe.domain.refuel.parseRefuelDecimal
import de.uhi.enia.ridesafe.domain.refuel.pricePerLiter
import de.uhi.enia.ridesafe.domain.refuel.refuelFormInitialValues
import de.uhi.enia.ridesafe.domain.refuel.refuelFromForm
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun RefuelFormScreen(
    modifier: Modifier = Modifier,
    vehicles: List<Vehicle>,
    existing: Refuel? = null,
    onSave: (Refuel, (Result<Unit>) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    // Regional conventions, not the in-app language's likely region (SET-07).
    val locale = formattingLocale()
    val currency = currentCurrencySetting().currency
    val fractionDigits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: 2
    val unitSystem = currentUnitSystem()
    val metric = usesMetric(unitSystem)
    val editInitial =
        remember(existing?.id, locale, unitSystem) {
            existing?.let { refuelFormInitialValues(it, unitSystem, locale) }
        }
    val initialDateTime =
        remember(existing?.id) {
            java.time.LocalDateTime.now()
        }

    var selectedVehicleId by rememberSaveable(existing?.id) { mutableStateOf(editInitial?.vehicleId) }
    var dateEpochDay by rememberSaveable(existing?.id) {
        mutableLongStateOf(editInitial?.dateEpochDay ?: initialDateTime.toLocalDate().toEpochDay())
    }
    var hour by rememberSaveable(existing?.id) { mutableIntStateOf(editInitial?.hour ?: initialDateTime.hour) }
    var minute by rememberSaveable(existing?.id) { mutableIntStateOf(editInitial?.minute ?: initialDateTime.minute) }
    var fuelText by
        rememberSaveable(existing?.id) {
            mutableStateOf(editInitial?.fuelText.orEmpty())
        }
    var totalText by
        rememberSaveable(existing?.id) {
            mutableStateOf(editInitial?.totalText.orEmpty())
        }
    var odometerText by
        rememberSaveable(existing?.id) {
            mutableStateOf(editInitial?.odometerText.orEmpty())
        }
    var fullTank by rememberSaveable(existing?.id) { mutableStateOf(editInitial?.fullTank ?: false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vehicles, selectedVehicleId) {
        if (existing == null && selectedVehicleId == null && vehicles.isNotEmpty()) {
            selectedVehicleId = vehicles.firstOrNull { it.isPrimary }?.id ?: vehicles.first().id
        }
    }

    val selectedVehicle = vehicles.firstOrNull { it.id == selectedVehicleId }
    val fuelDecimal = parseRefuelDecimal(fuelText)
    val totalDecimal = parseRefuelDecimal(totalText)
    val odometerDecimal = parseRefuelDecimal(odometerText)
    val fuelMilliliters = fuelDecimal?.let(::litersToMilliliters)
    val totalMinor = totalDecimal?.let { currencyUnitsToMinor(it, fractionDigits) }
    val odometerMeters = odometerDecimal?.let { odometerToMeters(it, unitSystem) }
    val fuelValid = fuelMilliliters != null && fuelMilliliters > 0
    val totalValid = totalMinor != null && totalMinor >= 0
    val odometerValid = odometerMeters != null && odometerMeters >= 0
    val saveEnabled = vehicles.isNotEmpty() && !saving
    val unitPrice =
        if (fuelValid && totalValid) pricePerLiter(totalMinor, fuelMilliliters, fractionDigits) else null
    val unitPriceText =
        unitPrice
            ?.let {
                NumberFormat.getCurrencyInstance(locale).apply { this.currency = currency }.format(it)
            }.orEmpty()

    fun save() {
        // Latch against a double-tapped save firing twice before the disabled button catches up.
        if (saving) return
        showErrors = true
        saveFailed = false
        val vehicle = vehicles.firstOrNull { it.id == selectedVehicleId }
        if (vehicle == null || !fuelValid || !totalValid || !odometerValid) return
        val edited =
            refuelFromForm(
                existing = existing,
                vehicleId = vehicle.id,
                dateEpochDay = dateEpochDay,
                hour = hour,
                minute = minute,
                fuelAmountMilliliters = fuelMilliliters,
                totalPriceMinor = totalMinor,
                currencyCode = currency.currencyCode,
                odometerMeters = odometerMeters,
                isFullTank = fullTank,
            ) ?: return
        saving = true
        onSave(edited) { result ->
            saving = false
            saveFailed = result.isFailure
            if (result.isSuccess) onBack()
        }
    }

    FormScaffold(
        title = stringResource(if (existing == null) R.string.refuel_add else R.string.refuel_edit),
        canSave = saveEnabled,
        onSave = ::save,
        onBack = onBack,
        modifier = modifier,
        backEnabled = !saving,
    ) {
        if (saveFailed) {
            Text(
                stringResource(R.string.refuel_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        VehicleDropdown(
            vehicles = vehicles,
            selected = selectedVehicle,
            onSelected = { selectedVehicleId = it.id },
            isError = showErrors && selectedVehicle == null,
            unavailableVehicle = existing != null && selectedVehicle == null,
            vehicleLocked = existing?.journeyAnchorRideId != null,
        )

        DateTimeFields(
            date = LocalDate.ofEpochDay(dateEpochDay),
            time = LocalTime.of(hour, minute),
            locale = locale,
            onDateClick = { showDatePicker = true },
            onTimeClick = { showTimePicker = true },
        )

        NumberField(
            value = fuelText,
            onValueChange = { fuelText = it },
            label = stringResource(R.string.refuel_fuel_amount),
            suffix = stringResource(R.string.unit_liter),
            isError = showErrors && !fuelValid,
            error = stringResource(R.string.refuel_error_fuel),
        )
        NumberField(
            value = totalText,
            onValueChange = { totalText = it },
            label = stringResource(R.string.refuel_total_price),
            suffix = currency.getSymbol(locale),
            isError = showErrors && !totalValid,
            error = stringResource(R.string.refuel_error_total),
        )
        OutlinedTextField(
            value = unitPriceText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.refuel_price_per_liter)) },
            suffix = { Text(stringResource(R.string.refuel_per_liter_suffix)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        NumberField(
            value = odometerText,
            onValueChange = { odometerText = it },
            label = stringResource(R.string.refuel_odometer),
            suffix = stringResource(if (metric) R.string.unit_km else R.string.unit_mi),
            isError = showErrors && !odometerValid,
            error = stringResource(R.string.refuel_error_odometer),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.refuel_full_tank), modifier = Modifier.weight(1f))
            Switch(checked = fullTank, onCheckedChange = { fullTank = it })
        }
    }

    if (showDatePicker) {
        RefuelDatePickerDialog(
            initialEpochDay = dateEpochDay,
            onPick = { dateEpochDay = it },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showTimePicker) {
        RefuelTimePickerDialog(
            hour = hour,
            minute = minute,
            onPick = { h, m ->
                hour = h
                minute = m
            },
            onDismiss = { showTimePicker = false },
        )
    }
}
