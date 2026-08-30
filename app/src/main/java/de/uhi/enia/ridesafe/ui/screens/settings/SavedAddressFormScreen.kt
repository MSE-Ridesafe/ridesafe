package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.rememberCameraPositionState
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.DEFAULT_PLACE_ICON
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.fixedIcon
import de.uhi.enia.ridesafe.data.hasFixedLabel
import de.uhi.enia.ridesafe.rides.processing.reverseGeocode
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.ConfirmDestructiveDialog
import de.uhi.enia.ridesafe.ui.components.DestructiveOutlinedButton
import de.uhi.enia.ridesafe.ui.components.FormScaffold
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatShortDistance
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private const val RADIUS_MIN = 25f
private const val RADIUS_MAX = 500f
private const val RADIUS_DEFAULT = 100
private const val RADIUS_STEPS = 18 // 25 m increments across 25..500

// Camera fallback when adding a place with no point yet (roughly the center of Germany).
private val FALLBACK_CENTER = LatLng(51.1657, 10.4515)

/**
 * Add/edit a saved address (ADR-03/04). [existing] null = add mode; non-null = edit, with fields
 * pre-filled and a delete action (UX-01). The GPS point is set by dragging/tapping the map, searching
 * an address (forward-geocode), or "use my location"; the recognition radius by a slider; and for a
 * custom place, an icon from a curated Material Symbols set. Shortcut kinds (Home/Work/School) keep a
 * fixed label and icon. Built from stock M3 components + the shared Google Map.
 */
@Composable
fun SavedAddressFormScreen(
    modifier: Modifier = Modifier,
    existing: SavedAddress?,
    presetKind: SavedPlaceKind,
    savedAddresses: List<SavedAddress> = emptyList(),
    onSave: (SavedAddress) -> Unit,
    onBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
    // Chromeless mode for the onboarding: no app bar (the wizard supplies back/skip chrome),
    // the save action pinned full-width at the bottom instead; onBack goes unused there.
    embedded: Boolean = false,
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val hasFixedLabel = presetKind.hasFixedLabel

    val shortcutLabel = stringResource(presetKind.labelRes())
    var label by
        rememberSaveable {
            mutableStateOf(existing?.label ?: if (presetKind == SavedPlaceKind.CUSTOM) "" else shortcutLabel)
        }
    var point by remember { mutableStateOf(existing?.let { LatLng(it.latitude, it.longitude) }) }
    var radius by rememberSaveable { mutableFloatStateOf((existing?.radiusMeters ?: RADIUS_DEFAULT).toFloat()) }
    var icon by rememberSaveable { mutableStateOf(existing?.icon ?: presetKind.fixedIcon() ?: DEFAULT_PLACE_ICON) }
    var resolvedAddress by remember { mutableStateOf(existing?.address) }
    var locationFailed by remember { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showMapPicker by rememberSaveable { mutableStateOf(false) }

    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(point ?: FALLBACK_CENTER, if (point != null) 15f else 5f)
        }
    val pickerCameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(point ?: FALLBACK_CENTER, if (point != null) 15f else 5f)
        }

    fun openMapPicker() {
        val center = point ?: cameraPositionState.position.target
        val zoom = if (point != null) maxOf(cameraPositionState.position.zoom, 15f) else cameraPositionState.position.zoom
        pickerCameraPositionState.position = CameraPosition.fromLatLngZoom(center, zoom)
        showMapPicker = true
    }
    // Recenter the camera when the point jumps via search / my-location (not on drag or tap).
    var recenterTo by remember { mutableStateOf<LatLng?>(null) }
    LaunchedEffect(recenterTo) {
        recenterTo?.let { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 15f)) }
    }
    // Reverse-geocode the point (debounced) to show/store the address for exact-match suppression (ADR-09).
    LaunchedEffect(point) {
        val p = point ?: return@LaunchedEffect
        delay(500.milliseconds)
        resolvedAddress = reverseGeocode(context, p.latitude, p.longitude)
    }

    val requestLocate =
        rememberLocationRequester(
            onLocated = { here ->
                locationFailed = false
                point = here
                recenterTo = here
            },
            onFailed = { locationFailed = true },
        )

    val search =
        rememberAddressSearch(near = point) { result ->
            val selected = LatLng(result.latitude, result.longitude)
            point = selected
            resolvedAddress = result.address
            recenterTo = selected
            focusManager.clearFocus()
            keyboard?.hide()
        }

    val canSave = point != null && label.isNotBlank()

    fun save() {
        val p = point ?: return
        val base =
            existing ?: SavedAddress(
                label = "",
                kind = presetKind,
                latitude = 0.0,
                longitude = 0.0,
                radiusMeters = RADIUS_DEFAULT,
                icon = icon,
            )
        onSave(
            base.copy(
                label = if (hasFixedLabel) shortcutLabel else label.trim(),
                kind = presetKind,
                latitude = p.latitude,
                longitude = p.longitude,
                radiusMeters = radius.toInt(),
                icon = presetKind.fixedIcon() ?: icon,
                address = resolvedAddress,
            ),
        )
    }

    FormScaffold(
        title = stringResource(if (existing != null) R.string.saved_address_edit_title else R.string.saved_address_new_title),
        canSave = canSave,
        onSave = ::save,
        onBack = onBack,
        modifier = modifier,
        embedded = embedded,
    ) {
        if (!search.active) {
            if (hasFixedLabel) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MaterialSymbol(symbolName = icon, contentDescription = null)
                    Text(shortcutLabel, style = MaterialTheme.typography.titleLarge)
                }
            } else {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.saved_address_label)) },
                    leadingIcon =
                        if (presetKind == SavedPlaceKind.GAS_STATION) {
                            { MaterialSymbol(symbolName = "local_gas_station", contentDescription = null) }
                        } else {
                            null
                        },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        AddressSearchField(state = search)

        if (search.active) {
            AddressSearchResults(
                state = search,
                near = point,
                savedAddresses = savedAddresses,
                editedId = existing?.id,
                unitSystem = unitSystem,
            )
        }

        if (!search.active) {
            PlaceMapPreviewCard(
                point = point,
                radiusMeters = radius.toDouble(),
                cameraPositionState = cameraPositionState,
                onOpenPicker = ::openMapPicker,
            )

            val hint =
                when {
                    point == null -> stringResource(R.string.saved_address_pick_hint)
                    locationFailed -> stringResource(R.string.saved_address_location_unavailable)
                    resolvedAddress != null -> shortAddress(resolvedAddress!!)
                    else -> null
                }
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(onClick = requestLocate, modifier = Modifier.fillMaxWidth()) {
                MaterialSymbol(symbolName = "my_location", contentDescription = null, size = 18.dp)
                Text(stringResource(R.string.saved_address_use_location), modifier = Modifier.padding(start = 8.dp))
            }

            Text(
                text = "${stringResource(R.string.saved_address_radius)}: ${formatShortDistance(radius.toDouble(), unitSystem)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = radius,
                onValueChange = { radius = it },
                valueRange = RADIUS_MIN..RADIUS_MAX,
                steps = RADIUS_STEPS,
            )

            if (presetKind.fixedIcon() == null) {
                Text(stringResource(R.string.saved_address_icon), style = MaterialTheme.typography.bodyLarge)
                PlaceIconPicker(selected = icon, onSelect = { icon = it })
            }

            if (onDelete != null) {
                DestructiveOutlinedButton(
                    label = stringResource(R.string.saved_address_delete),
                    onClick = { showDeleteDialog = true },
                )
            }
        }
    }

    if (showMapPicker) {
        PlaceMapPickerDialog(
            cameraPositionState = pickerCameraPositionState,
            radiusMeters = radius.toDouble(),
            onConfirm = { selected ->
                point = selected
                recenterTo = selected
                showMapPicker = false
            },
            onDismiss = { showMapPicker = false },
        )
    }

    if (showDeleteDialog && onDelete != null) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.saved_address_delete_confirm_title),
            message = stringResource(R.string.saved_address_delete_confirm_message, existing?.label.orEmpty()),
            onConfirm = onDelete,
            onDismiss = { showDeleteDialog = false },
        )
    }
}
