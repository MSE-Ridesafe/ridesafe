@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.BtDevice
import de.uhi.enia.ridesafe.rides.trigger.BluetoothDevices
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/** Picks from the phone's paired Bluetooth devices (GAR-08) — no need to be in the car. */
@Composable
internal fun BluetoothPickerDialog(
    devices: List<BluetoothDevices.Entry>,
    onPick: (BtDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { MaterialSymbol(symbolName = "bluetooth", contentDescription = null, size = 24.dp) },
        title = { Text(stringResource(R.string.vehicle_bluetooth_pick_title)) },
        text = {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.vehicle_bluetooth_pick_empty))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    devices.forEach { entry ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(BtDevice(entry.address, entry.name)) }
                                    .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MaterialSymbol(symbolName = "bluetooth", contentDescription = null, size = 20.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = entry.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
