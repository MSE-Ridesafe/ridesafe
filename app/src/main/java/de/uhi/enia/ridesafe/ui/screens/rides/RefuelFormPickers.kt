@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.uhi.enia.ridesafe.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Material date picker for the refuel form; hands back the picked day as an epoch day. */
@Composable
internal fun RefuelDatePickerDialog(
    initialEpochDay: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState =
        rememberDatePickerState(
            initialSelectedDateMillis =
                LocalDate
                    .ofEpochDay(initialEpochDay)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let {
                        onPick(
                            Instant
                                .ofEpochMilli(it)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay(),
                        )
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) { DatePicker(state = pickerState) }
}

/** Material time picker for the refuel form, honoring the device's 12/24-h convention. */
@Composable
internal fun RefuelTimePickerDialog(
    hour: Int,
    minute: Int,
    onPick: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pickerState =
        rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour =
                android.text.format.DateFormat
                    .is24HourFormat(context),
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(
                onClick = {
                    onPick(pickerState.hour, pickerState.minute)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
