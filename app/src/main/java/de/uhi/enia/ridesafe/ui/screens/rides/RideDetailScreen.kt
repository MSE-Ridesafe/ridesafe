@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.rides.processing.addressLines
import de.uhi.enia.ridesafe.rides.processing.latLngDistanceMeters
import de.uhi.enia.ridesafe.ui.components.DetailScaffold
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.SafetyScoreCard
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatRideDateTime
import de.uhi.enia.ridesafe.util.formatShortDistance
import de.uhi.enia.ridesafe.util.formatSpeed
import de.uhi.enia.ridesafe.util.formatTimeOfDay
import de.uhi.enia.ridesafe.util.haversineMeters

/**
 * Ride detail: the trip's numbers as a headline readout, the recorded route on a Google Map, the
 * journey, then the safety/eco judgments.
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
    modifier: Modifier = Modifier,
    ride: Ride?,
    route: List<LatLng>?,
    rideEvents: List<RideEvent>,
    startPlace: SavedAddress?,
    endPlace: SavedAddress?,
    analysisProgress: Float?,
    refuels: List<RefuelRow>,
    onOpenRefuel: (Long) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current
    DetailScaffold(
        title = { Text(ride?.let { formatRideDateTime(context, it.startedAtEpochMs) } ?: "") },
        onBack = onBack,
        showBack = showBack,
        modifier = modifier,
    ) {
        // ride is null only briefly while the Flow loads, or if it was removed.
        if (ride == null) return@DetailScaffold

        val durationSec = ride.endedAtEpochMs?.let { (it - ride.startedAtEpochMs) / 1000.0 }
        // Prefer the persisted metrics; fall back to computing from the (raw) route for a not-yet-processed ride.
        val distanceMeters = ride.distanceMeters ?: route?.takeIf { it.isNotEmpty() }?.let { latLngDistanceMeters(it) }
        val avgMps =
            ride.avgSpeedMps
                ?: if (distanceMeters != null && durationSec != null && durationSec > 0) distanceMeters / durationSec else null

        if (analysisProgress != null) {
            AnalysisNoticeCard(progress = analysisProgress)
        }
        RideStatsReadout(
            distance =
                distanceMeters?.let { formatDistance(it, unitSystem) }
                    ?: stringResource(R.string.value_not_set),
            duration = formatDuration(ride.startedAtEpochMs, ride.endedAtEpochMs),
            avgSpeed = avgMps?.let { formatSpeed(context, it, unitSystem) },
            maxSpeed = formatSpeed(context, ride.maxSpeedMps, unitSystem),
        )
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
        )

        // The ride in numbers, right under where it went: how far and how long, then how fast.
        // Duration lives here rather than in the journey card's footer, so the trip's magnitude
        // reads in one glance before the safety/eco judgments below.

        // The score sits directly under the map that shows its events (ANL-01). Three states:
        // scored; analysed but unscoreable (too little measurable driving — say so rather than
        // hiding, or the absence reads as a bug); not analysed / no motion sensors (nothing).
        ride.score?.let { SafetyScoreCard(score = it) }
            ?: ride.dynamics?.let {
                SafetyScoreCard(score = null, emptyText = stringResource(R.string.ride_score_unscoreable))
            }

        // Absent for a ride still being analysed and for one with no usable track — "no
        // profile", not "a perfectly efficient drive". Kinematic, so it needs no vehicle.
        ride.eco?.let { EcoCard(eco = it) }

        if (refuels.isNotEmpty()) {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.refuel_associated_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    refuels.sortedBy { it.refuel.timestampEpochMs }.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                        }
                        RefuelTimelineRow(
                            row = row,
                            selectionMode = false,
                            selected = false,
                            onClick = { onOpenRefuel(row.refuel.id) },
                            onLongClick = {},
                            showVehicle = false,
                        )
                    }
                }
            }
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
fun JourneyCard(stops: List<JourneyStop>) {
    if (stops.isEmpty()) return
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        JourneyTimeline(stops = stops, modifier = Modifier.padding(20.dp))
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
