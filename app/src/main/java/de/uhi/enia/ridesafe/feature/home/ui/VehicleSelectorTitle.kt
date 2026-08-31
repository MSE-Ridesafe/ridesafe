@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.home.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.data.entity.Vehicle
import de.uhi.enia.ridesafe.data.entity.displayTitle

/**
 * The screen title as the dashboard's scope: one car, or the whole garage. A tap opens the choice
 * as a plain [DropdownMenu] — deliberately not ExposedDropdownMenuBox, matching the logbook filter
 * sheet's precedent (FilterDropdown in [de.uhi.enia.ridesafe.feature.logbook.ui]).
 */
@Composable
internal fun VehicleSelectorTitle(
    vehicles: List<Vehicle>,
    selectedVehicleId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = vehicles.firstOrNull { it.id == selectedVehicleId }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        onClickLabel = stringResource(R.string.home_vehicle_filter_cd),
                        role = Role.DropdownList,
                    ) { expanded = true }
                    .minimumInteractiveComponentSize(),
        ) {
            Text(
                text = selected?.displayTitle() ?: stringResource(R.string.rides_filter_vehicle_any),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false: short titles keep the arrow snug; long ones truncate, arrow visible.
                modifier = Modifier.weight(1f, fill = false),
            )
            MaterialSymbol(symbolName = "arrow_drop_down", contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VehicleMenuItem(
                label = stringResource(R.string.rides_filter_vehicle_any),
                selected = selected == null,
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            vehicles.forEach { vehicle ->
                VehicleMenuItem(
                    label = vehicle.displayTitle(),
                    selected = vehicle.id == selectedVehicleId,
                    onClick = {
                        expanded = false
                        onSelect(vehicle.id)
                    },
                )
            }
        }
    }
}

/** One garage entry; the check sits trailing so unselected labels stay aligned with the selected one. */
@Composable
private fun VehicleMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon =
            if (selected) {
                { MaterialSymbol(symbolName = "check", contentDescription = null, size = 18.dp) }
            } else {
                null
            },
        onClick = onClick,
    )
}
