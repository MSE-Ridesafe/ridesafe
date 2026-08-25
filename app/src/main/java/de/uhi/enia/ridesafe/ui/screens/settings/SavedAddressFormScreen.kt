@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@file:SuppressLint("MissingPermission")

package de.uhi.enia.ridesafe.ui.screens.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberUpdatedMarkerState
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.DEFAULT_PLACE_ICON
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.fixedIcon
import de.uhi.enia.ridesafe.data.hasFixedLabel
import de.uhi.enia.ridesafe.rides.processing.forwardGeocode
import de.uhi.enia.ridesafe.rides.processing.reverseGeocode
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.map.FullScreenMap
import de.uhi.enia.ridesafe.ui.components.map.FullScreenMapRequest
import de.uhi.enia.ridesafe.ui.components.map.LocalFullScreenMap
import de.uhi.enia.ridesafe.ui.components.map.MapPreview
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatShortDistance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val RADIUS_MIN = 25f
private const val RADIUS_MAX = 500f
private const val RADIUS_DEFAULT = 100
private const val RADIUS_STEPS = 18 // 25 m increments across 25..500

// Camera fallback when adding a place with no point yet (roughly the centre of Germany).
private val FALLBACK_CENTER = LatLng(51.1657, 10.4515)
private val FALLBACK_FRAMING = listOf(LatLng(47.2, 5.8), LatLng(55.1, 15.1))

/** Curated Material Symbols offered for a custom place (ADR-06); the full font is thousands of glyphs. */
private val CURATED_PLACE_ICONS =
    listOf(
        "place",
        "home",
        "work",
        "school",
        "favorite",
        "star",
        "fitness_center",
        "restaurant",
        "local_cafe",
        "shopping_cart",
        "local_hospital",
        "directions_car",
        "local_gas_station",
        "flight",
        "park",
        "sports_soccer",
        "pets",
    )

/**
 * Add/edit a saved address (ADR-03/04). [existing] null = add mode; non-null = edit, with fields
 * pre-filled and a delete action (UX-01). The GPS point is set by dragging/tapping the map, searching
 * an address (forward-geocode), or "use my location"; the recognition radius by a slider; and for a
 * custom place, an icon from a curated Material Symbols set. Shortcut kinds (Home/Work/School) keep a
 * fixed label and icon. Built from stock M3 components + the shared Google Map.
 */
@Composable
fun SavedAddressFormScreen(
    existing: SavedAddress?,
    presetKind: SavedPlaceKind,
    onSave: (SavedAddress) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val fullScreenMap = LocalFullScreenMap.current
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
    var search by rememberSaveable { mutableStateOf("") }
    var searchFailed by remember { mutableStateOf(false) }
    var locationFailed by remember { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val formScrollState = rememberScrollState()
    // Reverse-geocode the point (debounced) to show/store the address for exact-match suppression (ADR-09).
    LaunchedEffect(point) {
        val p = point ?: return@LaunchedEffect
        delay(500.milliseconds)
        resolvedAddress = reverseGeocode(context, p.latitude, p.longitude)
    }

    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }

    fun locate() {
        locationFailed = false
        val cts = CancellationTokenSource()
        fused
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    val here = LatLng(loc.latitude, loc.longitude)
                    point = here
                } else {
                    locationFailed = true
                }
            }.addOnFailureListener { locationFailed = true }
    }
    val locationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) locate() else locationFailed = true
        }

    fun requestLocate() {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) locate() else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun runSearch() {
        keyboard?.hide()
        val query = search.trim()
        if (query.isEmpty()) return
        scope.launch {
            val result = forwardGeocode(context, query)
            if (result != null) {
                val found = LatLng(result.first, result.second)
                point = found
                searchFailed = false
            } else {
                searchFailed = true
            }
        }
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

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (existing != null) R.string.saved_address_edit_title else R.string.saved_address_new_title))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        MaterialSymbol(symbolName = "close", contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Button(modifier = Modifier.padding(end = 8.dp), onClick = ::save, enabled = canSave) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(formScrollState)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

            OutlinedTextField(
                value = search,
                onValueChange = {
                    search = it
                    searchFailed = false
                },
                label = { Text(stringResource(R.string.saved_address_search)) },
                singleLine = true,
                isError = searchFailed,
                supportingText = if (searchFailed) ({ Text(stringResource(R.string.saved_address_search_failed)) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                trailingIcon = {
                    IconButton(onClick = ::runSearch) {
                        MaterialSymbol(symbolName = "search", contentDescription = stringResource(R.string.saved_address_search))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            key(point) {
                val previewMarkerState = rememberUpdatedMarkerState(position = point ?: FALLBACK_CENTER)
                MapPreview(
                    framing = point?.let(::listOf) ?: FALLBACK_FRAMING,
                    height = 260.dp,
                    onExpand = {
                        val initialPoint = point
                        fullScreenMap.value =
                            FullScreenMapRequest { onClose ->
                                SavedAddressLocationPicker(
                                    initialPoint = initialPoint,
                                    radiusMeters = radius,
                                    onConfirm = { selected ->
                                        point = selected
                                        onClose()
                                    },
                                    onClose = onClose,
                                )
                            }
                    },
                    expandLabel = stringResource(R.string.saved_address_map_open),
                ) {
                    point?.let { p ->
                        Marker(state = previewMarkerState, title = stringResource(R.string.saved_address_marker))
                        Circle(
                            center = p,
                            radius = radius.toDouble(),
                            strokeColor = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4f,
                            fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        )
                    }
                }
            }

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

            OutlinedButton(onClick = ::requestLocate, modifier = Modifier.fillMaxWidth()) {
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CURATED_PLACE_ICONS.forEach { name ->
                        FilledIconToggleButton(
                            checked = icon == name,
                            onCheckedChange = { icon = name },
                        ) {
                            MaterialSymbol(symbolName = name, contentDescription = name)
                        }
                    }
                }
            }

            if (onDelete != null) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MaterialSymbol(symbolName = "delete", contentDescription = null, size = 18.dp)
                    Text(stringResource(R.string.saved_address_delete), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (showDeleteDialog && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { MaterialSymbol(symbolName = "delete", contentDescription = null, modifier = Modifier.size(24.dp)) },
            title = { Text(stringResource(R.string.saved_address_delete_confirm_title)) },
            text = { Text(stringResource(R.string.saved_address_delete_confirm_message, existing?.label.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SavedAddressLocationPicker(
    initialPoint: LatLng?,
    radiusMeters: Float,
    onConfirm: (LatLng) -> Unit,
    onClose: () -> Unit,
) {
    var selectedPoint by remember { mutableStateOf(initialPoint) }
    val markerState = rememberUpdatedMarkerState(position = selectedPoint ?: FALLBACK_CENTER)
    LaunchedEffect(markerState.position) {
        if (selectedPoint != null && markerState.position != selectedPoint) {
            selectedPoint = markerState.position
        }
    }

    Box(Modifier.fillMaxSize()) {
        FullScreenMap(
            framing = initialPoint?.let(::listOf) ?: FALLBACK_FRAMING,
            onClose = onClose,
            onMapClick = { selectedPoint = it },
        ) {
            selectedPoint?.let { selected ->
                Marker(
                    state = markerState,
                    draggable = true,
                    title = stringResource(R.string.saved_address_marker),
                )
                Circle(
                    center = selected,
                    radius = radiusMeters.toDouble(),
                    strokeColor = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4f,
                    fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
            }
        }

        Button(
            onClick = { selectedPoint?.let(onConfirm) },
            enabled = selectedPoint != null,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .fillMaxWidth(),
        ) {
            MaterialSymbol(symbolName = "check", contentDescription = null, size = 18.dp)
            Text(stringResource(R.string.saved_address_map_confirm), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
