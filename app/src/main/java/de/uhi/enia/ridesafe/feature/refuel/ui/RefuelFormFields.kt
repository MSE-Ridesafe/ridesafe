@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.refuel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.data.entity.Vehicle
import de.uhi.enia.ridesafe.data.entity.displayTitle
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
internal fun VehicleDropdown(
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
internal fun DateTimeFields(
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
