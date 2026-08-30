@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.displayTitle
import de.uhi.enia.ridesafe.permissions.AppPermission
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.rides.recording.RecordingStatus
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/**
 * Start a ride by hand (TRK-07) — for auto-tracking switched off, a car with no mapped device, or a
 * trigger that simply missed. Stopping belongs to the floating
 * [de.uhi.enia.ridesafe.ui.components.RecordingStatusBar], which is on screen in every tab while a
 * ride runs, so this button steps aside for it rather than offering the same thing twice.
 */
@Composable
internal fun RecordRideFab(
    vehicles: List<Vehicle>,
    onStartRide: (vehicleId: Long?) -> Unit,
) {
    val context = LocalContext.current
    val running by RecordingStatus.running.collectAsState()
    var picking by remember { mutableStateOf(false) }

    // Which car is this ride on (TRK-08)? Only worth asking when the garage leaves a choice — and
    // it has to be asked up front, since a ride's vehicle can't be changed afterwards.
    fun beginRide() {
        if (vehicles.size > 1) picking = true else onStartRide(vehicles.firstOrNull()?.id)
    }

    // A ride needs GPS, so this is where location gets requested (NFR-05): Settings only demands it
    // once automatic tracking is on, and manual recording works with automatic tracking off.
    val requestLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            PermissionState.refresh(context)
            if (granted) beginRide()
        }

    // Nothing to offer while a ride runs: the floating recording bar carries the clock and the
    // stop button, on this tab and every other one.
    AnimatedVisibility(
        visible = running == null,
        enter = scaleIn(),
        exit = scaleOut(),
    ) {
        ExtendedFloatingActionButton(
            onClick = {
                if (AppPermission.LOCATION.isGranted(context)) {
                    beginRide()
                } else {
                    requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            icon = { MaterialSymbol(symbolName = "play_arrow", contentDescription = null, fill = true) },
            text = { Text(stringResource(R.string.home_record_start)) },
        )
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.car_pick_vehicle)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    vehicles.forEach { vehicle ->
                        ListItem(
                            headlineContent = { Text(vehicle.displayTitle()) },
                            supportingContent =
                                vehicle.licensePlate
                                    .takeIf { it.isNotBlank() }
                                    ?.let { { Text(it) } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier =
                                Modifier.clickable {
                                    picking = false
                                    onStartRide(vehicle.id)
                                },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
