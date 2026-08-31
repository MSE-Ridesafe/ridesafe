package de.uhi.enia.ridesafe.feature.onboarding.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.entity.SavedPlaceKind
import de.uhi.enia.ridesafe.data.entity.Vehicle
import de.uhi.enia.ridesafe.feature.garage.ui.BluetoothPickerDialog
import de.uhi.enia.ridesafe.feature.garage.ui.TrackingCard
import de.uhi.enia.ridesafe.feature.garage.ui.VehicleFormScreen
import de.uhi.enia.ridesafe.feature.onboarding.OnboardingViewModel
import de.uhi.enia.ridesafe.feature.places.SavedAddressViewModel
import de.uhi.enia.ridesafe.feature.places.ui.SavedAddressFormScreen
import de.uhi.enia.ridesafe.permissions.AppPermission
import de.uhi.enia.ridesafe.permissions.PermissionAlertCard
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.recording.trigger.AutoTrackMode
import de.uhi.enia.ridesafe.recording.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.recording.trigger.BluetoothDevices
import de.uhi.enia.ridesafe.recording.trigger.applyAutoTrackMode
import kotlinx.coroutines.flow.flowOf

/**
 * ONB-02: create the first car (GAR-02) with the garage's real add form, so the fields, the
 * validation and the primary handling are exactly the Garage tab's. The form renders
 * chromeless — the wizard header is the only bar, and the form's save sits pinned at the
 * bottom like every other step's primary action; the page's own title stands in for the app
 * bar's. Coming back after saving re-opens the created car for editing (GAR-03) — a second
 * blank form here would only mint duplicate cars. Renders nothing for the frames the
 * re-opened car takes to load, since the form snapshots its initial fields.
 */
@Composable
internal fun CarPage(
    viewModel: OnboardingViewModel,
    vehicleId: Long?,
    onSave: (vehicle: Vehicle, makePrimary: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val existing by remember(vehicleId) {
        vehicleId?.let { viewModel.observeVehicle(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    if (vehicleId != null && existing == null) return
    Column(Modifier.fillMaxSize()) {
        StepFormHeader(
            title =
                stringResource(
                    if (existing != null) R.string.garage_edit_vehicle else R.string.garage_add_vehicle,
                ),
            body = stringResource(R.string.onboarding_car_intro),
        )
        VehicleFormScreen(
            existing = existing,
            onSave = onSave,
            onBack = onBack,
            embedded = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * ONB-03: map paired Bluetooth devices to the created car (GAR-08) — the key to auto-detection
 * (TRK-02) and to assigning rides to the right vehicle (TRK-08). Reuses the garage's tracking
 * card and picker, including the request-on-tap for BLUETOOTH_CONNECT.
 */
@Composable
internal fun BluetoothPage(
    viewModel: OnboardingViewModel,
    vehicleId: Long,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val vehicle by viewModel.observeVehicle(vehicleId).collectAsState(initial = null)
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showPicker = true
        }

    StepPage(primaryLabel = stringResource(R.string.onboarding_continue), onPrimary = onContinue) {
        StepIntro(
            symbolName = "bluetooth",
            title = stringResource(R.string.onboarding_bluetooth_title),
            body = stringResource(R.string.onboarding_bluetooth_body),
        )
        TrackingCard(
            devices = vehicle?.bluetoothDevices.orEmpty(),
            onLink = {
                if (AppPermission.BLUETOOTH.isGranted(context)) {
                    showPicker = true
                } else {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
            onRemove = { address ->
                vehicle?.let { v -> viewModel.unlinkDevice(v, address) }
            },
        )
    }

    if (showPicker) {
        val linkedAddresses =
            vehicle
                ?.bluetoothDevices
                .orEmpty()
                .map { it.address }
                .toSet()
        BluetoothPickerDialog(
            devices = BluetoothDevices.bonded(context).filterNot { it.address in linkedAddresses },
            onPick = { device ->
                showPicker = false
                vehicle?.let { v -> viewModel.linkDevice(v, device) }
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * ONB-04: opt into automatic recording (SET-06) — the one step that requests permissions, and
 * only after the user flips the switch, keeping the app's ask-when-enabled rule (NFR-05). The
 * existing Settings alert card handles the actual granting: request order, the settings
 * deep-link for background location, and the spent-dialog fallback. Enabling picks the
 * PAIRED_ONLY mode (the SET-06 default recommendation); the mode screen in Settings has the rest.
 */
@Composable
internal fun AutoTrackPage(onContinue: () -> Unit) {
    val context = LocalContext.current
    val enabled = AutoTrackPrefs.get(context) != AutoTrackMode.OFF

    // Grants can land in the system settings app (background location); re-read on return.
    LifecycleResumeEffect(enabled) {
        PermissionState.refresh(context)
        onPauseOrDispose { }
    }

    StepPage(primaryLabel = stringResource(R.string.onboarding_continue), onPrimary = onContinue) {
        StepIntro(
            symbolName = "autoplay",
            title = stringResource(R.string.onboarding_autotrack_title),
            body = stringResource(R.string.onboarding_autotrack_body),
        )
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_autotrack_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { turnOn ->
                        applyAutoTrackMode(context, if (turnOn) AutoTrackMode.PAIRED_ONLY else AutoTrackMode.OFF)
                        PermissionState.refresh(context)
                    },
                )
            }
        }
        // Renders only while something is missing, so granting everything clears the page down
        // to its switch — the built-in "you're done" signal.
        PermissionAlertCard()
    }
}

/**
 * ONB-05: save a first place (ADR-01/05) with the real address editor, preset to the Home
 * shortcut — or to a custom place on a replay where Home already exists (each shortcut is a
 * singleton). Saving goes through [SavedAddressViewModel] so rides are re-matched (ADR-07),
 * which matters when replaying with a logbook.
 */
@Composable
internal fun PlacePage(
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: SavedAddressViewModel = viewModel()
    val addresses by viewModel.addresses.collectAsState()
    // Latch against a double-tapped save inserting the place twice.
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        StepFormHeader(
            title = stringResource(R.string.saved_address_new_title),
            body = stringResource(R.string.onboarding_place_intro),
        )
        SavedAddressFormScreen(
            existing = null,
            presetKind =
                if (addresses.none { it.kind == SavedPlaceKind.HOME }) {
                    SavedPlaceKind.HOME
                } else {
                    SavedPlaceKind.CUSTOM
                },
            savedAddresses = addresses,
            onSave = {
                if (!saved) {
                    saved = true
                    viewModel.add(it)
                    onSaved()
                }
            },
            onBack = onBack,
            embedded = true,
            modifier = Modifier.weight(1f),
        )
    }
}
