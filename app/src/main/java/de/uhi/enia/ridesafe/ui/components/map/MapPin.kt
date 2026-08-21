package de.uhi.enia.ridesafe.ui.components.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberUpdatedMarkerState
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/** A pin's disc color and the matching "on" color for its symbol, so the two always contrast. */
data class PinColors(
    val container: Color,
    val content: Color,
)

/**
 * A map pin with everything already resolved out of the composition — a marker's content lambda runs
 * outside the caller's composition, so themes and strings have to be read before it, not inside it.
 */
data class MapPin(
    val key: Any,
    val position: LatLng,
    val symbol: String,
    val colors: PinColors,
    val title: String? = null,
    val snippet: String? = null,
)

/** One pin: a Material Symbol on a colored disc, outlined so it reads on any tile. */
@Composable
fun MapPinIcon(
    symbol: String,
    colors: PinColors,
    outline: Color = MaterialTheme.colorScheme.surface,
) {
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .background(colors.container, CircleShape)
                .border(2.dp, outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(
            symbolName = symbol,
            contentDescription = null, // the marker's title carries the meaning
            size = 18.dp,
            color = colors.content,
        )
    }
}

/**
 * [pins] as markers. [selectedKey] names the one to bring forward: pins metres apart overlap, and
 * the selected one coming to the front with its info window open is the only thing that tells them
 * apart. [outline] must be resolved by the caller for the same reason [MapPin] carries its colors.
 */
@Composable
@GoogleMapComposable
fun MapPinMarkers(
    pins: List<MapPin>,
    outline: Color,
    selectedKey: Any? = null,
) {
    pins.forEach { pin ->
        val markerState = rememberUpdatedMarkerState(position = pin.position)
        val isSelected = pin.key == selectedKey
        if (isSelected) {
            LaunchedEffect(pin.key) { markerState.showInfoWindow() }
        }
        MarkerComposable(
            keys = arrayOf(pin.key),
            state = markerState,
            title = pin.title,
            snippet = pin.snippet,
            zIndex = if (isSelected) 1f else 0f,
        ) {
            MapPinIcon(pin.symbol, pin.colors, outline)
        }
    }
}
