package de.uhi.enia.ridesafe.ui.screens.rides

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Polyline
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RideEventType
import de.uhi.enia.ridesafe.data.symbol
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.map.FullScreenMap
import de.uhi.enia.ridesafe.ui.components.map.FullScreenMapRequest
import de.uhi.enia.ridesafe.ui.components.map.LocalFullScreenMap
import de.uhi.enia.ridesafe.ui.components.map.MapFocus
import de.uhi.enia.ridesafe.ui.components.map.MapPin
import de.uhi.enia.ridesafe.ui.components.map.MapPinIcon
import de.uhi.enia.ridesafe.ui.components.map.MapPinMarkers
import de.uhi.enia.ridesafe.ui.components.map.MapPreview
import de.uhi.enia.ridesafe.ui.components.map.PinColors

/** The pin colors for each event type, resolved outside the map so markers don't re-read the theme. */
@Composable
private fun RideEventType.pinColors(): PinColors =
    with(MaterialTheme.colorScheme) {
        when (this@pinColors) {
            RideEventType.BRAKING -> PinColors(error, onError)
            RideEventType.ACCELERATION -> PinColors(tertiary, onTertiary)
            RideEventType.CORNERING -> PinColors(primary, onPrimary)
        }
    }

@Composable
private fun RideEventType.label(): String =
    stringResource(
        when (this) {
            RideEventType.BRAKING -> R.string.ride_event_braking
            RideEventType.ACCELERATION -> R.string.ride_event_acceleration
            RideEventType.CORNERING -> R.string.ride_event_cornering
        },
    )

/**
 * A ride's pins: each segment's start and end, then every detected event (ANL-01). There is
 * deliberately no display threshold, so the map is exactly what the detector decided, not a second
 * opinion on it. The only events dropped are those the GPS couldn't place.
 */
@Composable
private fun rideMapPins(
    segments: List<List<LatLng>>,
    rideEvents: List<RideEvent>,
): List<MapPin> {
    val startColors = PinColors(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
    val endColors = PinColors(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.colorScheme.inverseOnSurface)
    val startTitle = stringResource(R.string.ride_start_marker)
    val endTitle = stringResource(R.string.ride_end_marker)

    return segments.flatMapIndexed { index, points ->
        listOf(
            MapPin("start-$index", points.first(), "flag", startColors, startTitle),
            MapPin("end-$index", points.last(), "sports_score", endColors, endTitle),
        )
    } +
        rideEvents
            .filter { it.lat != null && it.lon != null }
            .map { event ->
                MapPin(
                    key = event.id,
                    position = LatLng(event.lat!!, event.lon!!),
                    symbol = event.type.symbol(),
                    colors = event.type.pinColors(),
                    title = event.type.label(),
                    snippet =
                        stringResource(
                            R.string.ride_event_detail,
                            "%.2f".format(event.peakG),
                            "%.1f".format(event.durationMs / 1000.0),
                        ),
                )
            }
}

/**
 * The route map card. [segments] is a list of disconnected polylines — one for a single ride, one per
 * stop for a merged ride (MRG-07). Null = still loading; all-empty = the ride(s) recorded no GPS.
 * Tapping it opens the same route full-screen, where [rideEvents] also get a sheet (ANL-01).
 */
@Composable
fun RouteMapCard(
    segments: List<List<LatLng>>?,
    rideEvents: List<RideEvent> = emptyList(),
) {
    val fullScreen = LocalFullScreenMap.current
    val drawn = segments?.filter { it.isNotEmpty() }.orEmpty()
    val outline = MaterialTheme.colorScheme.surface
    val routeColor = MaterialTheme.colorScheme.primary
    val pins = rideMapPins(drawn, rideEvents)

    MapPreview(
        framing = if (segments == null) null else drawn.flatten(),
        onExpand = { fullScreen.value = FullScreenMapRequest { onClose -> RideFullScreenMap(drawn, rideEvents, onClose) } },
        expandLabel = stringResource(R.string.ride_map_expand),
        empty = { NoGps() },
    ) {
        drawn.forEach { points -> Polyline(points = points, color = routeColor, width = 12f) }
        MapPinMarkers(pins = pins, outline = outline)
    }
}

/** The event the sheet has selected. [tick] counts the taps so re-tapping re-centers the map on it. */
private data class SelectedEvent(
    val id: Long,
    val tick: Int,
)

/** Peek height of the event sheet, above the navigation bar: drag handle + header + one row. */
private val EventSheetPeek = 148.dp

/** Zoom the camera goes to for a picked event, unless it's already closer in. */
private const val SELECTED_EVENT_ZOOM = 17f

/**
 * The ride's route full-screen, with its events listed in a bottom sheet over it (ANL-01). Tapping a
 * row selects that event's marker — camera to it, info window open, drawn above its neighbors, which
 * is the point: two events detected metres apart otherwise sit on top of each other with no way to
 * tell there are two. No events (or none the GPS could place) means no sheet at all.
 */
@Composable
private fun RideFullScreenMap(
    segments: List<List<LatLng>>,
    rideEvents: List<RideEvent>,
    onClose: () -> Unit,
) {
    val events =
        remember(rideEvents) {
            rideEvents.filter { it.lat != null && it.lon != null }.sortedBy { it.startOffsetMs }
        }
    var selected by remember { mutableStateOf<SelectedEvent?>(null) }
    val outline = MaterialTheme.colorScheme.surface
    val routeColor = MaterialTheme.colorScheme.primary
    val pins = rideMapPins(segments, rideEvents)
    val focus =
        selected?.let { pick ->
            pins.firstOrNull { it.key == pick.id }?.let { MapFocus(it.position, SELECTED_EVENT_ZOOM, pick.tick) }
        }

    FullScreenMap(
        framing = segments.flatten(),
        onClose = onClose,
        focus = focus,
        sheetPeek = if (events.isEmpty()) 0.dp else EventSheetPeek,
        sheet =
            if (events.isEmpty()) {
                null
            } else {
                {
                    RideEventSheet(
                        events = events,
                        selectedId = selected?.id,
                        // The sheet deliberately stays where it is, so a run of events can be
                        // stepped through without reopening it between taps.
                        onSelect = { event -> selected = SelectedEvent(event.id, (selected?.tick ?: 0) + 1) },
                    )
                }
            },
    ) {
        segments.forEach { points -> Polyline(points = points, color = routeColor, width = 12f) }
        MapPinMarkers(pins = pins, outline = outline, selectedKey = selected?.id)
    }
}

/** The events of one ride in the order they happened, as the full-screen map's bottom sheet. */
@Composable
private fun RideEventSheet(
    events: List<RideEvent>,
    selectedId: Long?,
    onSelect: (RideEvent) -> Unit,
) {
    Text(
        text = pluralStringResource(R.plurals.ride_events_count, events.size, events.size),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.size(8.dp))
    LazyColumn(contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
        items(events, key = { it.id }) { event ->
            RideEventRow(
                event = event,
                selected = event.id == selectedId,
                onClick = { onSelect(event) },
            )
        }
    }
}

/** One event in the sheet: the same pin the map draws, then what it was and when it happened. */
@Composable
private fun RideEventRow(
    event: RideEvent,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MapPinIcon(event.type.symbol(), event.type.pinColors(), MaterialTheme.colorScheme.surface)
        Column {
            Text(text = event.type.label(), style = MaterialTheme.typography.bodyLarge)
            Text(
                // Offset into the ride first ("4:12 in"), then the marker's own subtitle, so the row
                // and the info window it opens read the same.
                text =
                    DateUtils.formatElapsedTime(event.startOffsetMs / 1000) + " · " +
                        stringResource(
                            R.string.ride_event_detail,
                            "%.2f".format(event.peakG),
                            "%.1f".format(event.durationMs / 1000.0),
                        ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the card shows in place of a map when the ride recorded no GPS at all. */
@Composable
private fun NoGps() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MaterialSymbol(
            symbolName = "location_off",
            contentDescription = null,
            size = 40.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.ride_detail_no_gps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
