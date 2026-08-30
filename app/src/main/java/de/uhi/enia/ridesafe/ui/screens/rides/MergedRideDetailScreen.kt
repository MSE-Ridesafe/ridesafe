@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.summarizeMerge
import de.uhi.enia.ridesafe.domain.safetyScoreForRides
import de.uhi.enia.ridesafe.ui.components.DetailScaffold
import de.uhi.enia.ridesafe.ui.components.SafetyScoreCard
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDurationMs
import de.uhi.enia.ridesafe.util.formatRideDateTime
import de.uhi.enia.ridesafe.util.formatSpeed

/**
 * Detail of a merged ride (§3.8): the stops' routes drawn as disconnected polylines (MRG-07), the
 * aggregated metrics (MRG-05), and the list of stops with un-merge controls (MRG-04, MRG-11). [stops]
 * is chronological; [segments] holds one route per stop (null while loading). Un-merging happens via
 * [onUnmerge] (peel selected stops) and [onUnmergeAll]; the screen pops itself once fewer than two
 * stops remain, since the merged ride no longer exists.
 */
@Composable
fun MergedRideDetailScreen(
    modifier: Modifier = Modifier,
    stops: List<Ride>?,
    segments: List<List<LatLng>>?,
    rideEvents: List<RideEvent>,
    refuels: List<RefuelRow>,
    onOpenRefuel: (Long) -> Unit,
    onDetachRefuel: (Long) -> Unit,
    onBack: () -> Unit,
    onUnmergeAll: () -> Unit,
    onUnmerge: (stopIds: List<Long>) -> Unit,
    showBack: Boolean = true,
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current

    // Group gone (fully un-merged, here or elsewhere, down to one stop): leave the now-defunct merged
    // view. Null = still loading, so don't treat it as "gone".
    LaunchedEffect(stops?.size) {
        if (stops != null && stops.size < 2) onBack()
    }
    if (stops == null || stops.size < 2) {
        // Loading (or transitioning out): just the top bar with a back affordance, empty body.
        DetailScaffold(title = {}, onBack = onBack, showBack = showBack, modifier = modifier) {}
        return
    }

    val summary = remember(stops) { summarizeMerge(stops) }
    DetailScaffold(
        title = {
            Column {
                Text(formatRideDateTime(context, summary.startEpochMs))
                Text(
                    text =
                        stringResource(R.string.ride_merged_label) + " · " +
                            pluralStringResource(R.plurals.ride_stops_count, summary.stopCount, summary.stopCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onBack = onBack,
        showBack = showBack,
        modifier = modifier,
    ) {
        // The trip in numbers, same headline readout as a single ride's (duration = moving time).
        RideStatsReadout(
            distance =
                summary.distanceMeters?.let { formatDistance(it, unitSystem) }
                    ?: stringResource(R.string.value_not_set),
            duration = formatDurationMs(summary.movingDurationMs),
            avgSpeed = summary.avgSpeedMps?.let { formatSpeed(context, it, unitSystem) },
            maxSpeed = formatSpeed(context, summary.maxSpeedMps, unitSystem),
        )

        RouteMapCard(segments = segments, rideEvents = rideEvents)

        MergedJourneyCard(
            stops = stops,
            refuels = refuels,
            onOpenRefuel = onOpenRefuel,
            onDetachRefuel = onDetachRefuel,
            onUnmergeAll = onUnmergeAll,
            onUnmerge = onUnmerge,
        )

        // The whole trip's score: the stops' penalties and exposure summed, mapped once — never
        // an average of their scores (see SafetyScoreWindows). Hidden when no stop was scoreable.
        safetyScoreForRides(stops)?.let { SafetyScoreCard(score = it) }

        // The trip's efficiency, same card as a single ride's — the aggregates add up across
        // stops and the level is derived once from the whole trip's driving (MRG-05 rule).
        summary.eco?.let { EcoCard(eco = it) }
    }
}
