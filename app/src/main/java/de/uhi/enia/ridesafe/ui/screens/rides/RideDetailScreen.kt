@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.domain.ratedEco
import de.uhi.enia.ridesafe.domain.ratedScore
import de.uhi.enia.ridesafe.rides.processing.latLngDistanceMeters
import de.uhi.enia.ridesafe.rides.processing.score.ecoLevel
import de.uhi.enia.ridesafe.ui.components.CardDivider
import de.uhi.enia.ridesafe.ui.components.DetailScaffold
import de.uhi.enia.ridesafe.ui.components.SafetyScoreCard
import de.uhi.enia.ridesafe.ui.components.SectionCard
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatRideDateTime
import de.uhi.enia.ridesafe.util.formatSpeed
import de.uhi.enia.ridesafe.util.formatTimeOfDay

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

        JourneyCard(
            stops =
                listOf(
                    journeyStopFor(
                        ride.startAddress,
                        formatTimeOfDay(context, ride.startedAtEpochMs),
                        ride.startLat,
                        ride.startLon,
                        startPlace,
                        unitSystem,
                    ),
                    journeyStopFor(
                        ride.endAddress,
                        ride.endedAtEpochMs?.let { formatTimeOfDay(context, it) },
                        ride.endLat,
                        ride.endLon,
                        endPlace,
                        unitSystem,
                    ),
                ),
        )

        // The ride in numbers, right under where it went: how far and how long, then how fast.
        // Duration lives here rather than in the journey card's footer, so the trip's magnitude
        // reads in one glance before the safety/eco judgments below.

        // The score sits directly under the map that shows its events (ANL-01). Three states:
        // rated; analysed but unrated (too little measurable driving for the safety score OR the
        // eco level — the two rate together or not at all, see isRated — say so rather than
        // hiding, or the absence reads as a bug); not analysed / no motion sensors (nothing).
        ride.ratedScore?.let { SafetyScoreCard(score = it) }
            ?: ride.dynamics?.let {
                SafetyScoreCard(score = null, emptyText = stringResource(R.string.ride_score_unscoreable))
            }

        // Absent for a ride still being analysed and for one with no usable track — "no
        // profile", not "a perfectly efficient drive". Kinematic, so it needs no vehicle.
        // The level rides the same coupling as the score above: both or neither.
        ride.eco?.let { EcoCard(eco = it, level = ecoLevel(ride.ratedEco)) }

        if (refuels.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.refuel_associated_section)) {
                refuels.sortedBy { it.refuel.timestampEpochMs }.forEachIndexed { index, row ->
                    if (index > 0) CardDivider()
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
