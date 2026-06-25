@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.garage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.BtDevice
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.tracking.BluetoothDevices
import de.uhi.enia.ridesafe.ui.components.DetailCard
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatOdometer

@Composable
fun VehicleDetailScreen(
    vehicle: Vehicle?,
    unitSystem: UnitSystemSetting,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onLinkBluetooth: (BtDevice) -> Unit = {},
    onUnlinkBluetooth: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showBluetoothPicker by rememberSaveable { mutableStateOf(false) }
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showBluetoothPicker = true
        }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(vehicle?.displayTitle() ?: "") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
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
                    if (vehicle != null) {
                        IconButton(onClick = onEdit) {
                            MaterialSymbol(
                                symbolName = "edit",
                                contentDescription = stringResource(R.string.action_edit),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // vehicle is null only briefly while the Flow loads, or if it was removed.
        if (vehicle == null) return@Scaffold

        val notSet = stringResource(R.string.value_not_set)
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VehicleHeader(vehicle)

            OdometerCard(value = formatOdometer(context, vehicle.mileageKm, unitSystem))

            DetailCard(
                title = stringResource(R.string.vehicle_section_overview),
                rows =
                    listOf(
                        stringResource(R.string.vehicle_make) to vehicle.make,
                        stringResource(R.string.vehicle_model) to vehicle.model,
                        stringResource(R.string.vehicle_year) to (vehicle.year?.toString() ?: notSet),
                        stringResource(R.string.vehicle_license_plate) to vehicle.licensePlate,
                    ),
            )

            DetailCard(
                title = stringResource(R.string.vehicle_section_fuel),
                rows =
                    listOf(
                        stringResource(R.string.vehicle_fuel_type) to stringResource(vehicle.fuelType.labelRes()),
                        stringResource(R.string.vehicle_fuel_economy) to
                            (vehicle.fuelEconomy?.let { "$it ${stringResource(R.string.unit_fuel_economy)}" } ?: notSet),
                        stringResource(R.string.vehicle_tank_size) to
                            (vehicle.tankSize?.let { "$it ${stringResource(R.string.unit_liter)}" } ?: notSet),
                    ),
            )

            TrackingCard(
                devices = vehicle.bluetoothDevices,
                onLink = {
                    if (hasBluetoothConnect(context)) {
                        showBluetoothPicker = true
                    } else {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
                onRemove = onUnlinkBluetooth,
            )

            OutlinedButton(
                onClick = { showDeleteDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MaterialSymbol(symbolName = "delete", contentDescription = null, size = 18.dp)
                Text(
                    text = stringResource(R.string.garage_delete_vehicle),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    if (showDeleteDialog && vehicle != null) {
        DeleteVehicleDialog(
            vehicleName = vehicle.displayTitle(),
            onConfirm = onDelete,
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showBluetoothPicker) {
        val linkedAddresses =
            vehicle
                ?.bluetoothDevices
                .orEmpty()
                .map { it.address }
                .toSet()
        BluetoothPickerDialog(
            // Hide devices already linked to this vehicle.
            devices = BluetoothDevices.bonded(context).filterNot { it.address in linkedAddresses },
            onPick = { device ->
                showBluetoothPicker = false
                onLinkBluetooth(device)
            },
            onDismiss = { showBluetoothPicker = false },
        )
    }
}

private fun hasBluetoothConnect(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

/** Hero identity block: title image, make + model + optional nickname, license plate, primary badge. */
@Composable
private fun VehicleHeader(vehicle: Vehicle) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VehicleImage(size = 120.dp, color = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text(
            text = vehicle.displayTitle(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            shape = MaterialTheme.shapes.extraSmall,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Text(
                text = vehicle.licensePlate,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (vehicle.isPrimary) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.garage_primary)) },
                leadingIcon = {
                    MaterialSymbol(symbolName = "favorite", contentDescription = null, fill = true)
                },
                colors =
                    AssistChipDefaults.assistChipColors(
                        disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                        disabledLeadingIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
            )
        }
    }
}

/** Highlighted hero stat — the odometer is the vehicle's most-watched number. */
@Composable
private fun OdometerCard(value: String) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaterialSymbol(
                symbolName = "speed",
                contentDescription = null,
                size = 32.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.vehicle_mileage),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** Linked Bluetooth devices for auto-tracking (GAR-08): list with remove + a link action. */
@Composable
private fun TrackingCard(
    devices: List<BtDevice>,
    onLink: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.vehicle_section_tracking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
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
}

/** Picks from the phone's paired Bluetooth devices (GAR-08) — no need to be in the car. */
@Composable
private fun BluetoothPickerDialog(
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

@Preview
@Composable
private fun VehicleDetailPreview() {
    RidesafeTheme {
        VehicleDetailScreen(
            vehicle = previewVehicles.first(),
            unitSystem = UnitSystemSetting.METRIC,
            onBack = {},
            onEdit = {},
            onDelete = {},
        )
    }
}
