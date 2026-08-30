@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.DEFAULT_PLACE_ICON
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.displayTitle
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.animatePlacement
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatShortDate
import de.uhi.enia.ridesafe.util.toLocalDate

/** The garage's own symbol for a car, reused wherever the filters name a vehicle. */
internal const val VEHICLE_ICON = "directions_car"

/**
 * The filters currently narrowing the list, each removable on its own — so the user can see what
 * is hiding rides without reopening the sheet. Renders nothing when only a search term is set.
 */
@Composable
fun ActiveFilterChips(
    filter: RideFilter,
    vehicles: List<Vehicle>,
    places: List<SavedAddress>,
    onFilterChange: (RideFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val unitSystem = currentUnitSystem()

    // The row stays composed even with nothing in it: a chip that has just been removed from the
    // filter still has to be here to play its exit. With no chips it measures to zero height, so it
    // costs no space — hence the spacing living on the chips rather than on the row.
    //
    // The chips that stay glide to their new slots — including up a row — instead of snapping there
    // the moment one is removed; see animatePlacement. animateContentSize carries the row's own
    // height with them, so losing a line lifts the rides underneath instead of stepping them up.
    // Its spring matches the one animatePlacement uses, which keeps a chip travelling up a row in
    // step with the shrinking box it travels inside — that box clips, and a slower chip would be
    // cut off on the way.
    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .animateContentSize(),
    ) {
        AnimatedChip(
            spec =
                filter.vehicleId?.let { id ->
                    val name = vehicles.firstOrNull { it.id == id }?.displayTitle()
                    ChipSpec(name ?: stringResource(R.string.rides_filter_vehicle), VEHICLE_ICON)
                },
            onRemove = { onFilterChange(filter.copy(vehicleId = null)) },
        )
        AnimatedChip(
            spec =
                filter.startPlaceId?.let { id ->
                    val place = places.firstOrNull { it.id == id }
                    ChipSpec(
                        label = stringResource(R.string.rides_filter_chip_start, place?.label.orEmpty()),
                        // The place's own icon — the same one the list row shows for that endpoint (ADR-08).
                        icon = place?.icon ?: DEFAULT_PLACE_ICON,
                    )
                },
            onRemove = { onFilterChange(filter.copy(startPlaceId = null)) },
        )
        AnimatedChip(
            spec =
                filter.endPlaceId?.let { id ->
                    val place = places.firstOrNull { it.id == id }
                    ChipSpec(
                        label = stringResource(R.string.rides_filter_chip_end, place?.label.orEmpty()),
                        icon = place?.icon ?: DEFAULT_PLACE_ICON,
                    )
                },
            onRemove = { onFilterChange(filter.copy(endPlaceId = null)) },
        )
        AnimatedChip(
            spec =
                if (filter.fromEpochMs == null && filter.toEpochMs == null) {
                    null
                } else {
                    ChipSpec(dateChipLabel(context, filter), "date_range")
                },
            onRemove = { onFilterChange(filter.copy(fromEpochMs = null, toEpochMs = null)) },
        )
        AnimatedChip(
            spec =
                if (filter.minDistanceMeters == null && filter.maxDistanceMeters == null) {
                    null
                } else {
                    val min = filter.minDistanceMeters?.let { formatDistance(it, unitSystem) }
                    val max = filter.maxDistanceMeters?.let { formatDistance(it, unitSystem) }
                    ChipSpec(
                        label =
                            when {
                                min != null && max != null -> {
                                    stringResource(R.string.rides_filter_chip_distance_range, min, max)
                                }

                                min != null -> {
                                    stringResource(R.string.rides_filter_chip_distance_min, min)
                                }

                                else -> {
                                    stringResource(R.string.rides_filter_chip_distance_max, max.orEmpty())
                                }
                            },
                        icon = "straighten",
                    )
                },
            onRemove = { onFilterChange(filter.copy(minDistanceMeters = null, maxDistanceMeters = null)) },
        )
        AnimatedChip(
            spec = filter.tripType?.let { ChipSpec(stringResource(it.labelRes()), it.icon()) },
            onRemove = { onFilterChange(filter.copy(tripType = null)) },
        )
        AnimatedChip(
            spec =
                if (filter.onlyWithEvents) {
                    ChipSpec(stringResource(R.string.rides_filter_chip_events), "warning")
                } else {
                    null
                },
            onRemove = { onFilterChange(filter.copy(onlyWithEvents = false)) },
        )
    }
}

/** What one active-filter chip shows: its text and the symbol in front of it. */
private data class ChipSpec(
    val label: String,
    val icon: String,
)

/**
 * One filter chip, present while [spec] is non-null: it fades and slides on its way in and out, and
 * glides to its new slot when a neighbour goes away.
 *
 * The enter/exit deliberately only translate the chip — they never animate its width. A FlowRow
 * wraps on the size its children report, so a chip that shrinks on the way out gets narrow enough
 * to fit on the line above, jumps up there, and finishes vanishing at the end of the wrong row.

 */
@Composable
private fun AnimatedChip(
    spec: ChipSpec?,
    onRemove: () -> Unit,
) {
    // The chip holds on to what it last showed: by the time it animates away the filter it
    // described is already cleared, and reading it again would blank the label out mid-exit.
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec

    AnimatedVisibility(
        visible = spec != null,
        modifier = Modifier.animatePlacement(),
        enter = fadeIn() + slideInHorizontally { it / 4 },
        exit = fadeOut() + slideOutHorizontally { it / 4 },
    ) {
        shown?.let { RemovableChip(it.label, it.icon, onRemove) }
    }
}

@Composable
private fun RemovableChip(
    label: String,
    icon: String,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = true,
        onClick = onRemove,
        // The gap rides along with the chip so a removed one leaves none behind. Horizontal only:
        // a chip is a 32.dp pill inside a 48.dp touch target, so wrapped lines already sit 16.dp
        // apart on their own — adding to that made the rows read as far looser than the columns.
        modifier = Modifier.padding(end = 8.dp),
        label = { Text(label) },
        leadingIcon = { MaterialSymbol(symbolName = icon, contentDescription = null, size = 18.dp) },
        trailingIcon = {
            MaterialSymbol(
                symbolName = "close",
                contentDescription = stringResource(R.string.rides_filter_chip_remove),
                size = 18.dp,
            )
        },
    )
}

private fun dateChipLabel(
    context: Context,
    filter: RideFilter,
): String {
    val from = filter.fromEpochMs?.let { formatShortDate(context, it.toLocalDate()) }
    val to = filter.toEpochMs?.let { formatShortDate(context, (it - 1).toLocalDate()) }
    return when {
        from != null && to != null -> context.getString(R.string.rides_filter_chip_date_range, from, to)
        from != null -> context.getString(R.string.rides_filter_chip_date_from, from)
        else -> context.getString(R.string.rides_filter_chip_date_until, to.orEmpty())
    }
}
