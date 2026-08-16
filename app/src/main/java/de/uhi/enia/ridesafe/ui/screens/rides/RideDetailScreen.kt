@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RideEventType
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.haversineMeters
import de.uhi.enia.ridesafe.data.symbol
import de.uhi.enia.ridesafe.rides.processing.addressLines
import de.uhi.enia.ridesafe.rides.processing.latLngDistanceMeters
import de.uhi.enia.ridesafe.ui.components.DetailCard
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatRideDateTime
import de.uhi.enia.ridesafe.util.formatShortDistance
import de.uhi.enia.ridesafe.util.formatSpeed
import de.uhi.enia.ridesafe.util.formatTimeOfDay
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin

/**
 * Ride detail: the recorded route drawn on a Google Map, plus summary/speed/distance cards.
 * [route] is null while it's still loading; empty when the ride recorded no GPS. Distance and average
 * speed come from the persisted [Ride.distanceMeters]/[Ride.avgSpeedMps] (filled by the processing
 * pass ANL-02); they fall back to computing from [route] only for a ride not processed yet, where
 * [route] is the raw track (the simplified sidecar is only ever loaded once the columns are filled).
 *
 * [analysisProgress] is non-null only while this ride is still in the analysis queue (ANL-03), and
 * puts a notice at the top of the screen — without it, a half-analyzed ride just looks broken:
 * missing distance, no events, and nothing saying why.
 */
@Composable
fun RideDetailScreen(
    ride: Ride?,
    route: List<LatLng>?,
    rideEvents: List<RideEvent>,
    startPlace: SavedAddress?,
    endPlace: SavedAddress?,
    unitSystem: UnitSystemSetting,
    analysisProgress: Float?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(ride?.let { formatRideDateTime(context, it.startedAtEpochMs) } ?: "") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        MaterialSymbol(
                            symbolName = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        // ride is null only briefly while the Flow loads, or if it was removed.
        if (ride == null) return@Scaffold

        val durationSec = ride.endedAtEpochMs?.let { (it - ride.startedAtEpochMs) / 1000.0 }
        // Prefer the persisted metrics; fall back to computing from the (raw) route for a not-yet-processed ride.
        val distanceMeters = ride.distanceMeters ?: route?.takeIf { it.isNotEmpty() }?.let { latLngDistanceMeters(it) }
        val avgMps =
            ride.avgSpeedMps
                ?: if (distanceMeters != null && durationSec != null && durationSec > 0) distanceMeters / durationSec else null

        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (analysisProgress != null) {
                AnalysisNoticeCard(progress = analysisProgress)
            }

            RouteMapCard(segments = route?.let { listOf(it) }, rideEvents = rideEvents)

            // Build each stop, folding in a matched saved place (ADR-09): show "<address>, <dist> from
            // <label>", or just the label when the endpoint's address matches the place's exactly.
            fun stopFor(
                address: String?,
                time: String?,
                lat: Double?,
                lon: Double?,
                place: SavedAddress?,
            ): JourneyStop {
                val exact = place != null && address != null && address == place.address
                val distanceLabel =
                    if (place != null && !exact && lat != null && lon != null) {
                        formatShortDistance(haversineMeters(lat, lon, place.latitude, place.longitude), unitSystem)
                    } else {
                        null
                    }
                return JourneyStop(address, time, place = place, distanceLabel = distanceLabel, exactMatch = exact)
            }

            JourneyCard(
                stops =
                    listOf(
                        stopFor(
                            ride.startAddress,
                            formatTimeOfDay(context, ride.startedAtEpochMs),
                            ride.startLat,
                            ride.startLon,
                            startPlace,
                        ),
                        stopFor(
                            ride.endAddress,
                            ride.endedAtEpochMs?.let { formatTimeOfDay(context, it) },
                            ride.endLat,
                            ride.endLon,
                            endPlace,
                        ),
                    ),
                duration = formatDuration(ride.startedAtEpochMs, ride.endedAtEpochMs),
            )

            DetailCard(
                title = stringResource(R.string.ride_detail_section_speed),
                rows =
                    listOfNotNull(
                        stringResource(R.string.ride_detail_max_speed) to formatSpeed(context, ride.maxSpeedMps, unitSystem),
                        avgMps?.let {
                            stringResource(R.string.ride_detail_avg_speed) to formatSpeed(context, it, unitSystem)
                        },
                    ),
            )

            DetailCard(
                title = stringResource(R.string.ride_detail_section_distance),
                rows =
                    listOf(
                        stringResource(R.string.ride_detail_total_distance) to
                            (
                                distanceMeters?.let { formatDistance(it, unitSystem) }
                                    ?: stringResource(R.string.value_not_set)
                            ),
                    ),
            )
        }
    }
}

// Timeline column metrics: gap after the (content-sized) timestamp column, the icon gutter, its gap.
private val JourneyTimeGap = 12.dp
private val JourneyGutterWidth = 24.dp
private val JourneyGutterGap = 16.dp

/**
 * One stop in a ride's journey: an address and the time there (either may be null/unknown). [note] is
 * an optional extra line under the address — used by a merged ride's waypoints to show the departure
 * time + parked duration. [place] is the matched saved place (ADR-09) when the endpoint falls in one;
 * [distanceLabel] is the pre-formatted offset from its center (null when there's no place or it's an
 * [exactMatch] of the place's stored address).
 */
data class JourneyStop(
    val address: String?,
    val time: String?,
    val note: String? = null,
    val place: SavedAddress? = null,
    val distanceLabel: String? = null,
    val exactMatch: Boolean = false,
)

/**
 * A ride's journey as a card wrapping a stacked timeline (see [JourneyTimeline]) — the single-ride
 * detail's origin -> destination view.
 */
@Composable
fun JourneyCard(
    stops: List<JourneyStop>,
    duration: String?,
) {
    if (stops.isEmpty()) return
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        JourneyTimeline(stops = stops, duration = duration, modifier = Modifier.padding(20.dp))
    }
}

/**
 * A journey as a stacked timeline: each stop is an icon + address + time, joined by a continuous line.
 * Takes an arbitrary number of [stops] so a merged ride can render as one origin -> waypoints ->
 * destination chain — the first stop is the origin, the last the destination (a filled pin), any in
 * between are waypoints. Extracted from its card so a merged ride can embed it under its own header.
 */
@Composable
fun JourneyTimeline(
    stops: List<JourneyStop>,
    duration: String?,
    modifier: Modifier = Modifier,
) {
    if (stops.isEmpty()) return

    // Size the timestamp column to the widest time so it never clips (e.g. 12-hour "12:34 PM"),
    // then apply that one width to every row so the timeline stays aligned.
    val unknownTime = stringResource(R.string.value_not_set)
    val timeStyle = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val timeWidth =
        with(LocalDensity.current) {
            stops.maxOf { measurer.measure(it.time ?: unknownTime, timeStyle).size.width }.toDp() + 2.dp
        }

    Column(modifier = modifier) {
        stops.forEachIndexed { index, stop ->
            val isLast = index == stops.lastIndex
            JourneyStopRow(
                // A matched saved place shows its own icon in the timeline; otherwise the generic
                // origin ring / destination pin.
                icon = stop.place?.icon ?: if (isLast) "place" else "trip_origin",
                iconColor = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                iconFill = isLast,
                address = stop.address ?: stringResource(R.string.ride_address_unknown),
                time = stop.time ?: unknownTime,
                note = stop.note,
                timeWidth = timeWidth,
                lineAbove = index > 0,
                lineBelow = !isLast,
                place = stop.place,
                distanceLabel = stop.distanceLabel,
                exactMatch = stop.exactMatch,
            )
        }
        if (duration != null) {
            Spacer(Modifier.size(4.dp))
            // Bottom-left total time: schedule icon then duration, not aligned to the timeline columns.
            Row(verticalAlignment = Alignment.CenterVertically) {
                MaterialSymbol(
                    symbolName = "schedule",
                    contentDescription = stringResource(R.string.ride_detail_duration),
                    size = 16.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = duration,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One row of the journey timeline. The [time] sits in a [timeWidth]-wide column left of the gutter
 * (the caller sizes it to the widest time so all rows align); the icon sits in the gutter, both
 * vertically centered (the icon by the weighted line segments above/below it). [lineAbove]/
 * [lineBelow] draw the connector toward the adjacent stop, so stacked stops share one continuous line.
 */
@Composable
private fun JourneyStopRow(
    icon: String,
    iconColor: Color,
    address: String,
    time: String,
    timeWidth: Dp,
    lineAbove: Boolean,
    lineBelow: Boolean,
    iconFill: Boolean = false,
    note: String? = null,
    place: SavedAddress? = null,
    distanceLabel: String? = null,
    exactMatch: Boolean = false,
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        // Timestamp left of the timeline, vertically centered on the icon; left-aligned so it
        // shares the card's left edge with the total-duration footer.
        Box(
            modifier =
                Modifier
                    .width(timeWidth)
                    .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(JourneyTimeGap))
        Column(
            modifier =
                Modifier
                    .width(JourneyGutterWidth)
                    .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Connector(visible = lineAbove, modifier = Modifier.weight(1f))
            MaterialSymbol(
                symbolName = icon,
                contentDescription = null,
                size = 18.dp,
                fill = iconFill,
                color = iconColor,
            )
            Connector(visible = lineBelow, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.width(JourneyGutterGap))
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            if (place != null && exactMatch) {
                // Address equals the saved place's exactly — the timeline icon already shows the place,
                // so just its label (ADR-09).
                Text(
                    text = place.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                val (street, locality) = addressLines(address)
                Text(
                    text = street,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    overflow = TextOverflow.Ellipsis,
                )
                if (locality != null) {
                    Text(
                        text = locality,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (place != null && distanceLabel != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.saved_address_distance_from, distanceLabel, place.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A 2dp vertical line filling its (weighted) slot; invisible when [visible] is false, to keep spacing. */
@Composable
private fun Connector(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .width(2.dp)
                .then(
                    if (visible) Modifier.background(MaterialTheme.colorScheme.outlineVariant) else Modifier,
                ),
    )
}

/** A pin's disc colour and the matching "on" colour for its symbol, so the two always contrast. */
private data class PinColors(
    val container: Color,
    val content: Color,
)

/** The pin colours for each event type, resolved outside the map so markers don't re-read the theme. */
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

/** A map pin with everything already resolved out of the composition — an event or a route endpoint. */
private data class MapPin(
    val key: Any,
    val position: LatLng,
    val symbol: String,
    val colors: PinColors,
    val title: String,
    val snippet: String? = null,
)

/** One pin: a Material Symbol on a coloured disc, outlined so it reads on any tile. */
@Composable
private fun MapPinIcon(
    symbol: String,
    colors: PinColors,
    outline: Color,
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
 * The route map card. [segments] is a list of disconnected polylines — one for a single ride, one per
 * stop for a merged ride (MRG-07). Null = still loading; all-empty = the ride(s) recorded no GPS.
 * [rideEvents] are drawn as markers on the full-screen map (ANL-01).
 */
@Composable
fun RouteMapCard(
    segments: List<List<LatLng>>?,
    rideEvents: List<RideEvent> = emptyList(),
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp),
    ) {
        when {
            segments == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            segments.all { it.isEmpty() } -> {
                NoGps()
            }

            else -> {
                RouteMap(segments, rideEvents)
            }
        }
    }
}

@Composable
private fun RouteMap(
    segments: List<List<LatLng>>,
    rideEvents: List<RideEvent>,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        RouteMapContent(segments = segments, rideEvents = rideEvents, liteMode = true)
        // Lite-mode maps open the Google Maps app when tapped; this transparent overlay
        // swallows the tap and opens our own full-screen interactive map instead.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    onClickLabel = stringResource(R.string.ride_map_expand),
                    onClick = { expanded = true },
                ),
        )
    }

    if (expanded) {
        // Closing fades the dialog's own content out first and only then leaves composition.
        // Dismissing straight away tears the map down while the window is still animating out,
        // which flashes the backdrop the map sits on.
        var closing by remember { mutableStateOf(false) }
        val closeAlpha by animateFloatAsState(
            targetValue = if (closing) 0f else 1f,
            animationSpec = tween(200),
            finishedListener = { if (it == 0f) expanded = false },
            label = "mapDialogClose",
        )

        Dialog(
            onDismissRequest = { closing = true },
            // decorFitsSystemWindows = false lets the map fill behind the status/navigation bars
            // (no top/bottom safe-area insets); the close button below re-applies them.
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().alpha(closeAlpha)) {
                FullScreenMap(segments = segments, rideEvents = rideEvents, onClose = { closing = true })
            }
        }
    }
}

/**
 * The event the sheet has selected. [tick] counts the taps so re-tapping the same event re-centers
 * the map on it — without it, a second tap on an already-selected event would change nothing.
 */
private data class SelectedEvent(
    val id: Long,
    val tick: Int,
)

/** Peek height of the event sheet, above the navigation bar: drag handle + header + one row. */
private val EventSheetPeek = 148.dp

/**
 * The full-screen map, with the ride's events listed in a bottom sheet over it (ANL-01). Tapping a
 * row selects that event's marker — camera to it, info window open, drawn above its neighbours,
 * which is the point: two events detected metres apart otherwise sit on top of each other with no
 * way to tell there are two. No events (or none the GPS could place) means no sheet at all.
 */
@Composable
private fun FullScreenMap(
    segments: List<List<LatLng>>,
    rideEvents: List<RideEvent>,
    onClose: () -> Unit,
) {
    val events =
        remember(rideEvents) {
            rideEvents.filter { it.lat != null && it.lon != null }.sortedBy { it.startOffsetMs }
        }
    var selected by remember { mutableStateOf<SelectedEvent?>(null) }

    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            RouteMapContent(segments = segments, rideEvents = rideEvents, liteMode = false)
            CloseMapButton(onClose)
        }
        return
    }

    val scaffoldState = rememberBottomSheetScaffoldState()
    // The sheet floats over the map rather than shrinking it, so it also covers the navigation bar
    // and has to keep its own content clear of it.
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val peek = EventSheetPeek + navigationBar

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peek,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetShadowElevation = 6.dp,
        sheetContent = {
            RideEventSheet(
                events = events,
                selectedId = selected?.id,
                // The sheet deliberately stays where it is, so a run of events can be stepped
                // through without reopening it between taps.
                onSelect = { event -> selected = SelectedEvent(event.id, (selected?.tick ?: 0) + 1) },
            )
        },
    ) {
        // The scaffold's padding is deliberately ignored: the map draws behind the sheet, and keeps
        // the route framed in what's left visible via the same peek height.
        Box(Modifier.fillMaxSize()) {
            RouteMapContent(
                segments = segments,
                rideEvents = rideEvents,
                liteMode = false,
                selected = selected,
                bottomPadding = peek,
            )
            CloseMapButton(onClose)
        }
    }
}

/** The full-screen map's back-out control, inset clear of the status bar the map draws behind. */
@Composable
private fun BoxScope.CloseMapButton(onClose: () -> Unit) {
    IconButton(
        onClick = onClose,
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
    ) {
        MaterialSymbol(
            symbolName = "close",
            contentDescription = stringResource(R.string.ride_map_close),
        )
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

/** Zoom the camera goes to for a picked event, unless it's already closer in. */
private const val SELECTED_EVENT_ZOOM = 17f

/** Map dp per world at zoom 0, and the margin kept between the route and the map's edges. */
private const val WORLD_DP = 256.0
private const val MAP_PADDING_DP = 32.0

/**
 * A lite-mode map shows twice the area an interactive one shows at the same zoom, so its camera
 * needs one level more to frame the same route. Measured, not derived: fitting one ride both ways
 * put the two markers 870 px apart in lite mode against 432 px for the same computed camera.
 */
private const val LITE_ZOOM_BIAS = 1f

/** Web Mercator's y for a latitude, as used for the zoom that fits a bounding box. */
private fun mercatorY(latitude: Double): Double {
    val sin = sin(latitude * PI / 180).coerceIn(-0.9999, 0.9999)
    return ln((1 + sin) / (1 - sin)) / 2
}

/**
 * The camera that frames [points] in a [widthDp] × [heightDp] map, computed before the map exists.
 * This is what CameraUpdateFactory.newLatLngBounds works out internally, done up front so the
 * camera can be handed to GoogleMapOptions: a map born at the right place never shows the
 * world view → initial position → fitted route sequence that moving the camera after load gives.
 */
private fun fittingCamera(
    points: List<LatLng>,
    widthDp: Float,
    heightDp: Float,
    liteMode: Boolean,
): CameraPosition {
    val bias = if (liteMode) LITE_ZOOM_BIAS else 0f
    val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
    if (points.size < 2) return CameraPosition.fromLatLngZoom(bounds.center, 14f + bias)
    val latFraction = (mercatorY(bounds.northeast.latitude) - mercatorY(bounds.southwest.latitude)) / (2 * PI)
    val lngSpan = (bounds.northeast.longitude - bounds.southwest.longitude).let { if (it < 0) it + 360 else it }
    val zoom =
        minOf(
            log2((heightDp - 2 * MAP_PADDING_DP).coerceAtLeast(1.0) / WORLD_DP / latFraction),
            log2((widthDp - 2 * MAP_PADDING_DP).coerceAtLeast(1.0) / WORLD_DP / (lngSpan / 360)),
        )
    // A route with no extent at all divides by zero above; the coercion turns that into street zoom.
    return CameraPosition.fromLatLngZoom(bounds.center, zoom.coerceIn(2.0, 18.0).toFloat() + bias)
}

/**
 * The route drawn on a Google Map, framed to fit. [liteMode] true renders a static snapshot (the
 * card preview); false is a live, gesture-driven map. Gestures are kept 2D — pan/zoom/rotate on,
 * tilt off — and the toolbar is hidden so taps stay in-app rather than launching the Maps app.
 *
 * Dark mode is a JSON style rather than GoogleMap's mapColorScheme: lite mode ignores the colour
 * scheme but does honour JSON styling, and one mechanism keeps both maps looking the same. A style
 * can only be set after the map is created, so the map stays covered until it reports itself
 * loaded — otherwise it shows a light frame or two before the style lands.
 *
 * [rideEvents] become markers (ANL-01) on both maps; only the live one shows their info windows,
 * since the preview's taps are swallowed by the overlay that opens it full-screen. [selected] is the
 * event the bottom sheet picked, which the camera moves to; [bottomPadding] is how much of the map
 * that sheet covers, kept out of both the framing and the camera's idea of centre.
 */
@Composable
private fun RouteMapContent(
    segments: List<List<LatLng>>,
    rideEvents: List<RideEvent>,
    liteMode: Boolean,
    selected: SelectedEvent? = null,
    bottomPadding: Dp = 0.dp,
) {
    val drawn = segments.filter { it.isNotEmpty() }
    val allPoints = drawn.flatten()
    var mapLoaded by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val mapStyle =
        remember(dark) {
            MapStyleOptions.loadRawResourceStyle(context, if (dark) R.raw.map_style_night else R.raw.map_style_day)
        }
    val routeColor = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.surface
    val mapBackground = MaterialTheme.colorScheme.surfaceBright.toArgb()
    val startColors = PinColors(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
    val endColors = PinColors(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.colorScheme.inverseOnSurface)
    val startTitle = stringResource(R.string.ride_start_marker)
    val endTitle = stringResource(R.string.ride_end_marker)

    // Resolved out here rather than inside the map's content lambda: that scope is for map nodes,
    // not theme and string lookups. Every detected event is pinned — there is deliberately no
    // display threshold, so the map is exactly what the detector decided, not a second opinion on
    // it. The only events dropped are those the GPS couldn't place.
    val pins =
        drawn.flatMapIndexed { index, points ->
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

    // The map renders into a TextureView, which composites with whatever is drawn behind it in the
    // same hierarchy. Without an opaque layer there, everything the map draws with alpha — water,
    // rail, whole frames mid-zoom — blends with the screen behind the map instead. Same colour as
    // the loading cover above, so the fade between them shows no shift.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceBright)) {
        val camera =
            remember(allPoints, maxWidth, maxHeight, bottomPadding, liteMode) {
                fittingCamera(allPoints, maxWidth.value, (maxHeight - bottomPadding).value, liteMode)
            }
        val cameraPositionState = rememberCameraPositionState { position = camera }

        // Move to the sheet's pick, close enough to read it. Zoom only ever goes in: the map opens
        // framed on the whole route, and a pan alone leaves the pin as one dot among many.
        LaunchedEffect(selected) {
            val pin = pins.firstOrNull { it.key == selected?.id } ?: return@LaunchedEffect
            val zoom = maxOf(cameraPositionState.position.zoom, SELECTED_EVENT_ZOOM)
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(pin.position, zoom), 400)
        }

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            googleMapOptionsFactory = { GoogleMapOptions().liteMode(liteMode).camera(camera).backgroundColor(mapBackground) },
            contentPadding = PaddingValues(bottom = bottomPadding),
            properties = MapProperties(mapStyleOptions = mapStyle),
            uiSettings =
                MapUiSettings(
                    tiltGesturesEnabled = false,
                    mapToolbarEnabled = false,
                    zoomControlsEnabled = false,
                ),
            onMapLoaded = { mapLoaded = true },
        ) {
            drawn.forEach { points -> Polyline(points = points, color = routeColor, width = 12f) }
            pins.forEach { pin ->
                val markerState = rememberUpdatedMarkerState(position = pin.position)
                val isSelected = pin.key == selected?.id
                // Events detected metres apart overlap; the selected one comes to the front and
                // opens its info window, which is the only thing that tells the two of them apart.
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

        // The same placeholder the card shows while the route is still loading, so the two phases
        // read as one wait rather than a spinner followed by a flashing map. It fades rather than
        // pops, which also hides the frame or two the map spends applying its style.
        val coverAlpha by animateFloatAsState(if (mapLoaded) 0f else 1f, tween(400), label = "mapCover")
        if (coverAlpha > 0f) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .alpha(coverAlpha)
                        .background(MaterialTheme.colorScheme.surfaceBright),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }

    // Reveal the map anyway if it never reports itself loaded, so this can't sit on a spinner.
    // Long enough not to pre-empt a slow first load, which reveals a half-tiled map.
    LaunchedEffect(Unit) {
        delay(6_000)
        mapLoaded = true
    }
}

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
