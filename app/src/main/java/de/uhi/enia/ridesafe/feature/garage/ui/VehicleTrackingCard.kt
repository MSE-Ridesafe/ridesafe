@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.garage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.core.components.SectionCard
import de.uhi.enia.ridesafe.data.entity.BtDevice

/** Linked Bluetooth devices for auto-tracking (GAR-08): list with remove + a link action. */
@Composable
internal fun TrackingCard(
    devices: List<BtDevice>,
    onLink: () -> Unit,
    onRemove: (String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.vehicle_section_tracking)) {
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.vehicle_bluetooth_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        } else {
            devices.forEach { device ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MaterialSymbol(symbolName = "bluetooth", contentDescription = null, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(device.address) }) {
                        MaterialSymbol(
                            symbolName = "close",
                            contentDescription = stringResource(R.string.vehicle_bluetooth_remove),
                            size = 20.dp,
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onLink,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            MaterialSymbol(symbolName = "add", contentDescription = null, size = 18.dp)
            Text(
                text = stringResource(R.string.vehicle_bluetooth_link),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
