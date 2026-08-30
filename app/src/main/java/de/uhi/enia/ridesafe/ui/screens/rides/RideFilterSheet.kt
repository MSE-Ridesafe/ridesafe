@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.DEFAULT_PLACE_ICON
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.displayTitle
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.NumberField
import de.uhi.enia.ridesafe.util.METERS_PER_KM
import de.uhi.enia.ridesafe.util.METERS_PER_MILE
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatShortDate
import de.uhi.enia.ridesafe.util.startOfDayMs
import de.uhi.enia.ridesafe.util.toFieldText
import de.uhi.enia.ridesafe.util.toLocalDate
import de.uhi.enia.ridesafe.util.toMeters
import de.uhi.enia.ridesafe.util.toUtcDay
import de.uhi.enia.ridesafe.util.usesMetric
import de.uhi.enia.ridesafe.util.utcMillis
import java.time.LocalDate

/**
 * The filter sheet (LOG-06, LOG-07, LOG-12 … LOG-15). Changes apply to the list behind it
 * immediately — the primary button only reports how many rides are left and closes.
 */
@Composable
fun RideFilterSheet(
    filter: RideFilter,
    vehicles: List<Vehicle>,
    places: List<SavedAddress>,
    matchCount: Int,
    onFilterChange: (RideFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val unitSystem = currentUnitSystem()
    val metric = usesMetric(unitSystem)
    val perUnit = if (metric) METERS_PER_KM else METERS_PER_MILE
    val unitLabel = stringResource(if (metric) R.string.unit_km else R.string.unit_mi)

    // The distance fields keep their own text: re-deriving it from the stored meters on every
    // keystroke would fight the user over "1." and trailing zeros. Seeded when the sheet opens.
    var minText by remember { mutableStateOf(filter.minDistanceMeters.toFieldText(perUnit)) }
    var maxText by remember { mutableStateOf(filter.maxDistanceMeters.toFieldText(perUnit)) }

    // Opens straight to the top of the safe area and fills it, the way the Google apps' sheets do —
    // and not just for looks. AnchoredDraggable settles a fling with a *decay* animation, and that
    // decay writes the sheet's offset unclamped (AnchoredDraggable.animateToWithDecay: `dragTo(value)`
    // stops only once it crosses the target). A hard fling could therefore carry the sheet past its
    // topmost anchor, and from outside the anchor range it never came to rest — it oscillated
    // forever, replaying the same offsets bit for bit. Both a fling on the drag handle and one on
    // the content could trigger it.
    //
    // When the sheet already sits at its topmost anchor and that anchor is the top of the screen,
    // `prev == targetOffset` and animateToWithDecay skips the animation altogether: there is nothing
    // above to decay into. skipPartiallyExpanded removes the half-way anchor for the same reason.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // A LazyColumn rather than Column + verticalScroll: once the sheet is dragged to the top,
        // the sheet's own nested-scroll handling and a plain scroll container fight over the same
        // gesture, and the content bounces between them until the user swipes the other way. The
        // navigation-bar inset sits on the container, not inside the scrolled content, for the same
        // reason — an inset that grows the content is a feedback loop waiting to happen.
        LazyColumn(
            // Fills the sheet so its expanded anchor lands at the top of the screen — see above.
            modifier = Modifier.fillMaxHeight(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.rides_filter_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            item {
                FilterDropdown(
                    label = stringResource(R.string.rides_filter_vehicle),
                    selectedId = filter.vehicleId,
                    anyLabel = stringResource(R.string.rides_filter_vehicle_any),
                    anyIcon = VEHICLE_ICON,
                    options = vehicles.map { FilterOption(it.id, it.displayTitle(), VEHICLE_ICON) },
                    onSelected = { onFilterChange(filter.copy(vehicleId = it)) },
                )
            }
            item {
                FilterDropdown(
                    label = stringResource(R.string.rides_filter_start_place),
                    selectedId = filter.startPlaceId,
                    anyLabel = stringResource(R.string.rides_filter_place_any),
                    anyIcon = DEFAULT_PLACE_ICON,
                    options = places.map { FilterOption(it.id, it.label, it.icon) },
                    onSelected = { onFilterChange(filter.copy(startPlaceId = it)) },
                )
            }
            item {
                FilterDropdown(
                    label = stringResource(R.string.rides_filter_end_place),
                    selectedId = filter.endPlaceId,
                    anyLabel = stringResource(R.string.rides_filter_place_any),
                    anyIcon = DEFAULT_PLACE_ICON,
                    options = places.map { FilterOption(it.id, it.label, it.icon) },
                    onSelected = { onFilterChange(filter.copy(endPlaceId = it)) },
                )
            }

            item { SectionLabel(stringResource(R.string.rides_filter_date)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateBoundButton(
                        label = stringResource(R.string.rides_filter_date_from),
                        day = filter.fromEpochMs?.let { it.toLocalDate() },
                        modifier = Modifier.weight(1f),
                        // Inclusive lower bound: the picked day from its first millisecond.
                        onPicked = { onFilterChange(filter.copy(fromEpochMs = it?.startOfDayMs())) },
                    )
                    DateBoundButton(
                        label = stringResource(R.string.rides_filter_date_to),
                        // Stored exclusive (start of the next day), so the day picked is the one before.
                        day = filter.toEpochMs?.let { (it - 1).toLocalDate() },
                        modifier = Modifier.weight(1f),
                        onPicked = { onFilterChange(filter.copy(toEpochMs = it?.plusDays(1)?.startOfDayMs())) },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.rides_filter_distance)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = stringResource(R.string.rides_filter_distance_min, unitLabel),
                        value = minText,
                        modifier = Modifier.weight(1f),
                        onValueChange = {
                            minText = it
                            onFilterChange(filter.copy(minDistanceMeters = it.toMeters(perUnit)))
                        },
                    )
                    NumberField(
                        label = stringResource(R.string.rides_filter_distance_max, unitLabel),
                        value = maxText,
                        modifier = Modifier.weight(1f),
                        onValueChange = {
                            maxText = it
                            onFilterChange(filter.copy(maxDistanceMeters = it.toMeters(perUnit)))
                        },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.rides_filter_trip_type)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filter.tripType == null,
                        onClick = { onFilterChange(filter.copy(tripType = null)) },
                        label = { Text(stringResource(R.string.rides_filter_trip_any)) },
                    )
                    TripType.entries.forEach { type ->
                        FilterChip(
                            selected = filter.tripType == type,
                            onClick = { onFilterChange(filter.copy(tripType = type)) },
                            label = { Text(stringResource(type.labelRes())) },
                        )
                    }
                }
            }

            item {
                FilterChip(
                    selected = filter.onlyWithEvents,
                    onClick = { onFilterChange(filter.copy(onlyWithEvents = !filter.onlyWithEvents)) },
                    label = { Text(stringResource(R.string.rides_filter_events)) },
                    leadingIcon =
                        if (filter.onlyWithEvents) {
                            { MaterialSymbol(symbolName = "check", contentDescription = null, size = 18.dp) }
                        } else {
                            null
                        },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            minText = ""
                            maxText = ""
                            // The search term is the user's other half of the query and stays put.
                            onFilterChange(RideFilter(query = filter.query))
                        },
                    ) {
                        Text(stringResource(R.string.rides_filter_reset))
                    }
                    Button(onClick = onDismiss) {
                        Text(pluralStringResource(R.plurals.rides_filter_apply, matchCount, matchCount))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One dropdown entry: the value it selects, its label, and the symbol shown beside it. */
private data class FilterOption(
    val id: Long,
    val label: String,
    val icon: String,
)

/**
 * A dropdown over [options] with an "any" entry that clears the filter. The selected entry's own
 * icon shows on the button and beside every option, so which place is picked is visible at a glance
 * rather than only readable in the label.
 *
 * A plain button plus [DropdownMenu] rather than an ExposedDropdownMenuBox: that component keeps
 * re-measuring its anchor against the window, and inside a bottom sheet — whose own height follows
 * its content — the two feed each other and the sheet never stops settling.

 */
@Composable
private fun FilterDropdown(
    label: String,
    selectedId: Long?,
    anyLabel: String,
    anyIcon: String,
    options: List<FilterOption>,
    onSelected: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            MaterialSymbol(symbolName = selected?.icon ?: anyIcon, contentDescription = null, size = 20.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.rides_filter_labeled_value, label, selected?.label ?: anyLabel),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MaterialSymbol(symbolName = "arrow_drop_down", contentDescription = null, size = 20.dp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(anyLabel) },
                leadingIcon = { MaterialSymbol(symbolName = anyIcon, contentDescription = null) },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { MaterialSymbol(symbolName = option.icon, contentDescription = null) },
                    onClick = {
                        onSelected(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * One end of the date range. The picker's "Clear" action drops the bound again, which is what makes
 * either end optional; tapping outside leaves it as it was.
 */
@Composable
private fun DateBoundButton(
    label: String,
    day: LocalDate?,
    onPicked: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        // The label rides along on the face: two buttons reading just "Any" would be indistinguishable.
        Text(
            text =
                stringResource(
                    R.string.rides_filter_labeled_value,
                    label,
                    day?.let { formatShortDate(context, it) } ?: stringResource(R.string.rides_filter_date_any),
                ),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (open) {
        // The picker works in UTC millis, so a day goes in and comes back out at UTC midnight.
        val state = rememberDatePickerState(initialSelectedDateMillis = day?.utcMillis())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPicked(state.selectedDateMillis?.toUtcDay())
                        open = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onPicked(null)
                        open = false
                    },
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
        ) {
            DatePicker(state = state, title = { SectionLabel(label) })
        }
    }
}
