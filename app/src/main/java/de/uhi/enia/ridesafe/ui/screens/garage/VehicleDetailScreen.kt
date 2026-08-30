@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.garage

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.BtDevice
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.displayTitle
import de.uhi.enia.ridesafe.permissions.AppPermission
import de.uhi.enia.ridesafe.rides.trigger.BluetoothDevices
import de.uhi.enia.ridesafe.ui.components.ConfirmDestructiveDialog
import de.uhi.enia.ridesafe.ui.components.DestructiveOutlinedButton
import de.uhi.enia.ridesafe.ui.components.DetailCard
import de.uhi.enia.ridesafe.ui.components.DetailScaffold
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatOdometer

@Composable
fun VehicleDetailScreen(
    modifier: Modifier = Modifier,
    vehicle: Vehicle?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    showBack: Boolean = true,
    onChooseImage: (Uri) -> Unit,
    onRemoveImage: () -> Unit,
    onLinkBluetooth: (BtDevice) -> Unit = {},
    onUnlinkBluetooth: (String) -> Unit = {},
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showBluetoothPicker by rememberSaveable { mutableStateOf(false) }
    var showExtendedInformation by rememberSaveable { mutableStateOf(false) }
    var showPhotoSheet by rememberSaveable { mutableStateOf(false) }
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showBluetoothPicker = true
        }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onChooseImage(uri)
        }
    DetailScaffold(
        title = { Text(vehicle?.nicknameTitle() ?: "") },
        onBack = onBack,
        showBack = showBack,
        modifier = modifier,
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
    ) {
        // vehicle is null only briefly while the Flow loads, or if it was removed.
        if (vehicle == null) return@DetailScaffold

        val notSet = stringResource(R.string.value_not_set)
        // Cheap stat call, re-checked whenever the row is touched — setVehicleImage/removeVehicleImage
        // bump updatedAtEpochMs, the same key VehicleImage reloads on.
        val hasImage =
            remember(vehicle.vehicleUuid, vehicle.updatedAtEpochMs) {
                vehicleImageFile(context, vehicle).exists()
            }
        VehicleHeader(
            vehicle = vehicle,
            hasImage = hasImage,
            onEditImage = {
                // With a photo the tap asks what to do with it; without one the only sensible
                // answer is the picker, so it opens directly instead of behind a one-option sheet.
                if (hasImage) {
                    showPhotoSheet = true
                } else {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            },
        )

        DetailCard(
            title = stringResource(R.string.vehicle_section_overview),
            rows =
                listOf(
                    stringResource(R.string.vehicle_make) to vehicle.make,
                    stringResource(R.string.vehicle_model) to vehicle.model,
                    stringResource(R.string.vehicle_year) to (vehicle.year?.toString() ?: notSet),
                    stringResource(R.string.vehicle_license_plate) to vehicle.licensePlate,
                    stringResource(R.string.vehicle_mileage) to formatOdometer(vehicle.mileageKm, unitSystem),
                ),
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

            DetailCard(
                title = stringResource(R.string.vehicle_section_information),
                rows =
                    listOf(
                        stringResource(R.string.vehicle_type) to (vehicle.vehicleType ?: notSet),
                        stringResource(R.string.vehicle_engine) to (vehicle.engine ?: notSet),
                        stringResource(R.string.vehicle_manufacturing_country) to
                            (vehicle.manufacturingCountry ?: notSet),
                    ),
            )

            TrackingCard(
                devices = vehicle.bluetoothDevices,
                onLink = {
                    if (AppPermission.BLUETOOTH.isGranted(context)) {
                        showBluetoothPicker = true
                    } else {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
                onRemove = onUnlinkBluetooth,
            )
        }

        DestructiveOutlinedButton(
            label = stringResource(R.string.garage_delete_vehicle),
            onClick = { showDeleteDialog = true },
        )
    }

    if (showPhotoSheet) {
        VehiclePhotoSheet(
            onReplace = {
                showPhotoSheet = false
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onRemove = {
                showPhotoSheet = false
                onRemoveImage()
            },
            onDismiss = { showPhotoSheet = false },
        )
    }

    if (showDeleteDialog && vehicle != null) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.garage_delete_confirm_title),
            message = stringResource(R.string.garage_delete_confirm_message, vehicle.displayTitle()),
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

/** Hero identity block: photo and the vehicle's nickname use the full available width. */
@Composable
private fun VehicleHeader(
    vehicle: Vehicle,
    hasImage: Boolean,
    onEditImage: () -> Unit,
) {
    val editImageLabel =
        stringResource(if (hasImage) R.string.vehicle_photo_edit else R.string.vehicle_choose_image)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            VehicleImage(
                modifier = Modifier.clickable(onClickLabel = editImageLabel, onClick = onEditImage),
                vehicle = vehicle,
                size = 120.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClickLabel = editImageLabel, onClick = onEditImage)
                        .padding(7.dp),
                contentAlignment = Alignment.Center,
            ) {
                MaterialSymbol(
                    symbolName = if (hasImage) "photo_camera" else "add_a_photo",
                    contentDescription = editImageLabel,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 20.dp,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = vehicle.makeAndModel(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
}
