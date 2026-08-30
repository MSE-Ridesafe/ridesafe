@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.displayTitle
import de.uhi.enia.ridesafe.ui.components.FormScaffold
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.NumberField
import de.uhi.enia.ridesafe.util.currentCurrencySetting
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formattingLocale
import de.uhi.enia.ridesafe.util.usesMetric
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

@Composable
fun RefuelLoadingScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    RefuelStateScreen(onBack = onBack, showBack = showBack, modifier = modifier) { CircularProgressIndicator() }
}

@Composable
fun RefuelUnavailableScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    RefuelStateScreen(onBack = onBack, showBack = showBack, modifier = modifier) {
        Text(
            stringResource(R.string.refuel_not_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RefuelStateScreen(
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.refuel_edit)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            MaterialSymbol(symbolName = "close", contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
fun RefuelFormScreen(
    modifier: Modifier = Modifier,
    vehicles: List<Vehicle>,
    existing: Refuel? = null,
    onSave: (Refuel, (Result<Unit>) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
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
        val timestamp =
            runCatching {
                LocalDate
                    .ofEpochDay(dateEpochDay)
                    .atTime(hour, minute)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull() ?: return
        saving = true
        val edited =
            existing?.copy(
                vehicleId = vehicle.id,
                timestampEpochMs = timestamp,
                fuelAmountMilliliters = fuelMilliliters,
                totalPriceMinor = totalMinor,
                currencyCode = currency.currencyCode,
                odometerMeters = odometerMeters,
                isFullTank = fullTank,
            ) ?: Refuel(
                vehicleId = vehicle.id,
                timestampEpochMs = timestamp,
                fuelAmountMilliliters = fuelMilliliters,
                totalPriceMinor = totalMinor,
                currencyCode = currency.currencyCode,
                odometerMeters = odometerMeters,
                isFullTank = fullTank,
            )
        onSave(
            edited,
        ) { result ->
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
        val pickerState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    LocalDate
                        .ofEpochDay(dateEpochDay)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            dateEpochDay =
                                Instant
                                    .ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toEpochDay()
                        }
                        showDatePicker = false
                    },
                ) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (showTimePicker) {
        val pickerState =
            rememberTimePickerState(
                initialHour = hour,
                initialMinute = minute,
                is24Hour =
                    android.text.format.DateFormat
                        .is24HourFormat(context),
            )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        hour = pickerState.hour
                        minute = pickerState.minute
                        showTimePicker = false
                    },
                ) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun VehicleDropdown(
    vehicles: List<Vehicle>,
    selected: Vehicle?,
    onSelected: (Vehicle) -> Unit,
    isError: Boolean,
    unavailableVehicle: Boolean,
    vehicleLocked: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (vehicles.isNotEmpty() && !vehicleLocked) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.displayTitle().orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = vehicles.isNotEmpty() && !vehicleLocked,
            label = { Text(stringResource(R.string.refuel_vehicle)) },
            placeholder = {
                Text(
                    stringResource(
                        if (unavailableVehicle) R.string.refuel_vehicle_unavailable else R.string.refuel_vehicle_required,
                    ),
                )
            },
            isError = isError,
            supportingText =
                if (vehicleLocked) {
                    { Text(stringResource(R.string.refuel_vehicle_locked)) }
                } else if (vehicles.isEmpty() || isError) {
                    {
                        Text(
                            stringResource(
                                if (unavailableVehicle) {
                                    R.string.refuel_vehicle_unavailable_select
                                } else {
                                    R.string.refuel_vehicle_required
                                },
                            ),
                        )
                    }
                } else {
                    null
                },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            vehicles.forEach { vehicle ->
                DropdownMenuItem(
                    text = { Text(vehicle.displayTitle()) },
                    onClick = {
                        onSelected(vehicle)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DateTimeFields(
    date: LocalDate,
    time: LocalTime,
    locale: Locale,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
) {
    val dateText =
        DateFormat
            .getDateInstance(
                DateFormat.MEDIUM,
                locale,
            ).format(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()))
    val timeText =
        DateFormat
            .getTimeInstance(
                DateFormat.SHORT,
                locale,
            ).format(Date.from(date.atTime(time).atZone(ZoneId.systemDefault()).toInstant()))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = dateText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.refuel_date)) },
            trailingIcon = {
                IconButton(onClick = onDateClick) {
                    MaterialSymbol(symbolName = "calendar_month", contentDescription = stringResource(R.string.refuel_date))
                }
            },
            modifier = Modifier.weight(1f).clickable(onClick = onDateClick),
        )
        OutlinedTextField(
            value = timeText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.refuel_time)) },
            trailingIcon = {
                IconButton(onClick = onTimeClick) {
                    MaterialSymbol(symbolName = "schedule", contentDescription = stringResource(R.string.refuel_time))
                }
            },
            modifier = Modifier.weight(1f).clickable(onClick = onTimeClick),
        )
    }
}
