@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@file:SuppressLint("MissingPermission")

package de.uhi.enia.ridesafe.ui.screens.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.DEFAULT_PLACE_ICON
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.fixedIcon
import de.uhi.enia.ridesafe.data.hasFixedLabel
import de.uhi.enia.ridesafe.data.haversineMeters
import de.uhi.enia.ridesafe.rides.processing.AddressSearchResult
import de.uhi.enia.ridesafe.rides.processing.addressLines
import de.uhi.enia.ridesafe.rides.processing.forwardGeocodeSuggestions
import de.uhi.enia.ridesafe.rides.processing.reverseGeocode
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatShortDistance
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private const val RADIUS_MIN = 25f
private const val RADIUS_MAX = 500f
private const val RADIUS_DEFAULT = 100
private const val RADIUS_STEPS = 18 // 25 m increments across 25..500
private const val SEARCH_DEBOUNCE_MS = 400L
private const val SEARCH_MIN_LENGTH = 3
private const val SEARCH_SUGGESTION_LIMIT = 5
private const val SEARCH_HISTORY_PREFS = "saved_address_search"
private const val SEARCH_HISTORY_KEY = "recent_queries"

// Camera fallback when adding a place with no point yet (roughly the centre of Germany).
private val FALLBACK_CENTER = LatLng(51.1657, 10.4515)

private fun loadRecentAddressSearches(context: android.content.Context): List<String> =
    runCatching {
        val encoded =
            context
                .getSharedPreferences(SEARCH_HISTORY_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(SEARCH_HISTORY_KEY, null) ?: return@runCatching emptyList()
        val array = JSONArray(encoded)
        (0 until array.length()).mapNotNull { array.optString(it).trim().ifBlank { null } }.take(SEARCH_SUGGESTION_LIMIT)
    }.getOrDefault(emptyList())

private fun recordRecentAddressSearch(
    context: android.content.Context,
    query: String,
): List<String> {
    val trimmed = query.trim()
    val updated =
        (listOf(trimmed) + loadRecentAddressSearches(context).filterNot { it.equals(trimmed, ignoreCase = true) })
            .filter(String::isNotBlank)
            .take(SEARCH_SUGGESTION_LIMIT)
    context
        .getSharedPreferences(SEARCH_HISTORY_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString(SEARCH_HISTORY_KEY, JSONArray(updated).toString())
        .apply()
    return updated
}

private fun findExistingSavedPlace(
    result: AddressSearchResult,
    savedAddresses: List<SavedAddress>,
    editedId: Long?,
): SavedAddress? {
    val normalizedResult = normalizeSearchAddress(result.address)
    return savedAddresses
        .asSequence()
        .filterNot { it.id == editedId }
        .map { saved -> saved to haversineMeters(saved.latitude, saved.longitude, result.latitude, result.longitude) }
        .filter { (saved, distance) ->
            val sameAddress =
                saved.address
                    ?.let(::normalizeSearchAddress)
                    ?.takeIf(String::isNotEmpty) == normalizedResult
            sameAddress || distance <= 15.0
        }.minByOrNull { it.second }
        ?.first
}

private fun normalizeSearchAddress(value: String): String =
    value.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)

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
    savedAddresses: List<SavedAddress> = emptyList(),
    onSave: (SavedAddress) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
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
    var search by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf(emptyList<AddressSearchResult>()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchCompleted by remember { mutableStateOf(false) }
    var recentSearches by remember { mutableStateOf(loadRecentAddressSearches(context)) }
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
                    recenterTo = here
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

    LaunchedEffect(search, searchActive) {
        val query = search.trim()
        if (!searchActive || query.length < SEARCH_MIN_LENGTH) {
            searchResults = emptyList()
            searchLoading = false
            searchCompleted = false
            return@LaunchedEffect
        }
        searchLoading = true
        searchCompleted = false
        delay(SEARCH_DEBOUNCE_MS)
        val near = point
        searchResults =
            forwardGeocodeSuggestions(context, query, limit = SEARCH_SUGGESTION_LIMIT)
                .sortedBy { result ->
                    near?.let { haversineMeters(it.latitude, it.longitude, result.latitude, result.longitude) }
                        ?: Double.MAX_VALUE
                }
        searchLoading = false
        searchCompleted = true
    }

    fun chooseSearchResult(result: AddressSearchResult) {
        val selected = LatLng(result.latitude, result.longitude)
        point = selected
        resolvedAddress = result.address
        recenterTo = selected
        search = shortAddress(result.address)
        searchActive = false
        focusManager.clearFocus()
        keyboard?.hide()
        recentSearches = recordRecentAddressSearch(context, search)
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!searchActive) {
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

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.saved_address_search)) },
                leadingIcon = { MaterialSymbol(symbolName = "search", contentDescription = null) },
                trailingIcon = {
                    when {
                        searchLoading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        search.isNotEmpty() -> {
                            IconButton(onClick = { search = "" }) {
                                MaterialSymbol(
                                    symbolName = "close",
                                    contentDescription = stringResource(R.string.saved_address_search_clear),
                                )
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            if (search.trim().length >= SEARCH_MIN_LENGTH) {
                                recentSearches = recordRecentAddressSearch(context, search)
                            }
                            keyboard?.hide()
                        },
                    ),
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) searchActive = true },
            )

            if (searchActive) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        if (search.isBlank()) {
                            if (recentSearches.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.saved_address_search_recent),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                )
                                recentSearches.forEach { recent ->
                                    ListItem(
                                        headlineContent = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = { MaterialSymbol(symbolName = "history", contentDescription = null) },
                                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                        modifier = Modifier.clickable { search = recent },
                                    )
                                }
                            }
                        } else if (search.trim().length < SEARCH_MIN_LENGTH) {
                            Text(
                                text = stringResource(R.string.saved_address_search_more_characters),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else if (!searchLoading && searchCompleted && searchResults.isEmpty()) {
                            Text(
                                text = stringResource(R.string.saved_address_search_no_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            searchResults.forEach { result ->
                                val lines = addressLines(result.address)
                                val matched = findExistingSavedPlace(result, savedAddresses, existing?.id)
                                val distance =
                                    point?.let {
                                        formatShortDistance(
                                            haversineMeters(it.latitude, it.longitude, result.latitude, result.longitude),
                                            unitSystem,
                                        )
                                    }
                                ListItem(
                                    headlineContent = { Text(lines.first, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Column {
                                            listOfNotNull(lines.second, distance).takeIf { it.isNotEmpty() }?.let {
                                                Text(it.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            matched?.let {
                                                Text(
                                                    text = stringResource(R.string.saved_address_search_already_saved, it.label),
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    },
                                    leadingContent = { MaterialSymbol(symbolName = "location_on", contentDescription = null) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                    modifier = Modifier.clickable { chooseSearchResult(result) },
                                )
                            }
                        }
                    }
                }
            }

            if (!searchActive) {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                modifier = Modifier.fillMaxWidth().height(260.dp),
            ) {
                Box(Modifier.fillMaxSize()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        uiSettings =
                            MapUiSettings(
                                scrollGesturesEnabled = false,
                                zoomGesturesEnabled = false,
                                rotationGesturesEnabled = false,
                                tiltGesturesEnabled = false,
                                mapToolbarEnabled = false,
                                zoomControlsEnabled = false,
                            ),
                    ) {
                        point?.let { p ->
                            Circle(
                                center = p,
                                radius = radius.toDouble(),
                                strokeColor = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4f,
                                fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            )
                        }
                    }
                    if (point != null) {
                        MaterialSymbol(
                            symbolName = "location_on",
                            contentDescription = null,
                            fill = true,
                            size = 48.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.Center).offset(y = (-24).dp),
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .clickable(onClickLabel = stringResource(R.string.saved_address_map_open), onClick = ::openMapPicker),
                    )
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
    }

    if (showMapPicker) {
        Dialog(
            onDismissRequest = { showMapPicker = false },
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(Modifier.fillMaxSize()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = pickerCameraPositionState,
                        uiSettings =
                            MapUiSettings(
                                tiltGesturesEnabled = false,
                                mapToolbarEnabled = false,
                                zoomControlsEnabled = false,
                            ),
                    ) {
                        Circle(
                            center = pickerCameraPositionState.position.target,
                            radius = radius.toDouble(),
                            strokeColor = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4f,
                            fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        )
                    }

                    // This is deliberately not a map Marker: the map moves underneath it, so the
                    // pin remains perfectly centered throughout pan, fling, pinch and zoom gestures.
                    MaterialSymbol(
                        symbolName = "location_on",
                        contentDescription = stringResource(R.string.saved_address_marker),
                        fill = true,
                        size = 56.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center).offset(y = (-28).dp),
                    )

                    TopAppBar(
                        title = { Text(stringResource(R.string.saved_address_map_picker_title)) },
                        navigationIcon = {
                            IconButton(onClick = { showMapPicker = false }) {
                                MaterialSymbol(
                                    symbolName = "close",
                                    contentDescription = stringResource(R.string.action_cancel),
                                )
                            }
                        },
                        actions = {
                            FilledIconButton(
                                onClick = {
                                    val selected = pickerCameraPositionState.position.target
                                    point = selected
                                    recenterTo = selected
                                    showMapPicker = false
                                },
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                MaterialSymbol(
                                    symbolName = "check",
                                    contentDescription = stringResource(R.string.action_done),
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            ),
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.saved_address_map_picker_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
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
