package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.map.MapPreview
import de.uhi.enia.ridesafe.ui.components.map.MapSurface

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
        "local_parking",
        "directions_car",
        "flight",
        "beach_access",
        "park",
        "sports_soccer",
        "pets",
    )

/** The curated icon chooser for a custom place (ADR-06). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaceIconPicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CURATED_PLACE_ICONS.forEach { name ->
            FilledIconToggleButton(
                checked = selected == name,
                onCheckedChange = { onSelect(name) },
            ) {
                MaterialSymbol(symbolName = name, contentDescription = name)
            }
        }
    }
}

/**
 * The place's point and radius as a [MapPreview] card — the same shared surface (dark style,
 * deferred load, one spinner cover) the ride maps use; the whole card opens the full-screen
 * picker. The pin rides MapPreview's overlay slot rather than being a marker: the camera is
 * centered on the point, so the overlay is exact — and the slot keeps it under the loading
 * cover, so no pin floats over the spinner while the map loads or the device is offline.
 */
@Composable
internal fun PlaceMapPreviewCard(
    point: LatLng?,
    radiusMeters: Double,
    cameraPositionState: CameraPositionState,
    onOpenPicker: () -> Unit,
) {
    MapPreview(
        framing = null,
        cameraPositionState = cameraPositionState,
        height = 260.dp,
        onExpand = onOpenPicker,
        expandLabel = stringResource(R.string.saved_address_map_open),
        overlay = {
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
        },
    ) {
        point?.let { p ->
            Circle(
                center = p,
                radius = radiusMeters,
                strokeColor = MaterialTheme.colorScheme.primary,
                strokeWidth = 4f,
                fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            )
        }
    }
}

/**
 * Full-screen point picker on the shared [MapSurface] (dark style, loading cover, offline
 * handling): a free camera with a fixed center pin — deliberately not a map Marker, so the pin
 * remains perfectly centered throughout pan, fling, pinch and zoom gestures. Confirm hands back
 * the camera's target.
 *
 * Plain in-hierarchy content for [de.uhi.enia.ridesafe.ui.components.map.FullScreenMapHost],
 * never a Dialog: the Maps SDK leaves some features partly transparent, and a dialog's own
 * window lets the compositor blend that alpha over the screen behind it (see
 * [de.uhi.enia.ridesafe.ui.components.map.FullScreenMapRequest]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceMapPicker(
    cameraPositionState: CameraPositionState,
    radiusMeters: Double,
    onConfirm: (LatLng) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(Modifier.fillMaxSize()) {
            MapSurface(
                framing = emptyList(),
                liteMode = false,
                cameraPositionState = cameraPositionState,
            ) {
                Circle(
                    center = cameraPositionState.position.target,
                    radius = radiusMeters,
                    strokeColor = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4f,
                    fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
            }

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
                    IconButton(onClick = onClose) {
                        MaterialSymbol(
                            symbolName = "close",
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                actions = {
                    FilledIconButton(
                        onClick = { onConfirm(cameraPositionState.position.target) },
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
