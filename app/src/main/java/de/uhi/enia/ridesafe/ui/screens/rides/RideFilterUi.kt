@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.DEFAULT_PLACE_ICON
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.rideDay
import de.uhi.enia.ridesafe.util.usesMetric
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The garage's own symbol for a car, reused wherever the filters name a vehicle. */
private const val VEHICLE_ICON = "directions_car"

/** OutlinedTextField's own default height — the filter button matches it so the row lines up. */
private val SEARCH_FIELD_HEIGHT = 56.dp

private const val METERS_PER_KM = 1000.0
private const val METERS_PER_MILE = 1609.344

/**
 * The Logbook's search field (LOG-11) with the filter button beside it, badged with how many
 * filters are narrowing the list. Sits above the list rather than hiding behind a search icon, so
 * both affordances are visible without a tap.
 */
@Composable
fun RideSearchBar(
    query: String,
    activeFilterCount: Int,
    onQueryChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            placeholder = { Text(stringResource(R.string.rides_search_hint)) },
            leadingIcon = { MaterialSymbol(symbolName = "search", contentDescription = null) },
            trailingIcon =
                if (query.isEmpty()) {
                    null
                } else {
                    {
                        // A bare IconButton: the field is already the container, so a filled one
                        // would sit inside it as a second surface.
                        IconButton(onClick = { onQueryChange("") }) {
                            MaterialSymbol(
                                symbolName = "close",
                                contentDescription = stringResource(R.string.rides_search_clear),
                            )
                        }
                    }
                },
        )
        BadgedBox(
            badge = { if (activeFilterCount > 0) Badge { Text(activeFilterCount.toString()) } },
        ) {
            // Sized to the text field's own height so the two read as one control, rather than a
            // button parked next to a bar.
            FilledTonalIconButton(onClick = onOpenFilters, modifier = Modifier.size(SEARCH_FIELD_HEIGHT)) {
                MaterialSymbol(
                    symbolName = "tune",
                    contentDescription = stringResource(R.string.rides_filter_open),
                )
            }
        }
    }
}

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

/**
 * Slides content from where it was to where the layout just put it.
 *
 * Only the placement moves: the content is never re-measured, so it keeps its own size all the way
 * and the parent keeps seeing that size. Modifier.animateBounds would be the one-liner for this,
 * but it measures its content at the *animated* size (Constraints.fixed) and reports that size
 * upwards — so a chip in flight both stretches and feeds a size that is only passing through back
 * into the FlowRow's wrapping.
 */
@Composable
private fun Modifier.animatePlacement(): Modifier {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var animation by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }

    return this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val anim = animation ?: Animatable(target, IntOffset.VectorConverter).also { animation = it }
            if (anim.targetValue != target) {
                scope.launch { anim.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) }
            }
            // Place the chip where it used to be, then let the animation carry that offset to zero.
            anim.value - target
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
                        day = filter.fromEpochMs?.let { rideDay(it) },
                        modifier = Modifier.weight(1f),
                        // Inclusive lower bound: the picked day from its first millisecond.
                        onPicked = { onFilterChange(filter.copy(fromEpochMs = it?.startOfDayMs())) },
                    )
                    DateBoundButton(
                        label = stringResource(R.string.rides_filter_date_to),
                        // Stored exclusive (start of the next day), so the day picked is the one before.
                        day = filter.toEpochMs?.let { rideDay(it - 1) },
                        modifier = Modifier.weight(1f),
                        onPicked = { onFilterChange(filter.copy(toEpochMs = it?.plusDays(1)?.startOfDayMs())) },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.rides_filter_distance)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DistanceField(
                        label = stringResource(R.string.rides_filter_distance_min, unitLabel),
                        text = minText,
                        modifier = Modifier.weight(1f),
                        onChange = {
                            minText = it
                            onFilterChange(filter.copy(minDistanceMeters = it.toMeters(perUnit)))
                        },
                    )
                    DistanceField(
                        label = stringResource(R.string.rides_filter_distance_max, unitLabel),
                        text = maxText,
                        modifier = Modifier.weight(1f),
                        onChange = {
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
                    day?.let { formatFilterDate(context, it) } ?: stringResource(R.string.rides_filter_date_any),
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

@Composable
private fun DistanceField(
    label: String,
    text: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** The symbol for a trip kind: the list's own route glyph for a single ride, the merge one for a trip. */
private fun TripType.icon(): String =
    when (this) {
        TripType.SINGLE -> "route"
        TripType.MERGED -> "merge"
    }

private fun TripType.labelRes(): Int =
    when (this) {
        TripType.SINGLE -> R.string.rides_filter_trip_single
        TripType.MERGED -> R.string.rides_filter_trip_merged
    }

/** "24 Aug 2026" — short enough for a chip and a half-width button. */
private fun formatFilterDate(
    context: Context,
    day: LocalDate,
): String =
    DateUtils.formatDateTime(
        context,
        day.startOfDayMs(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
    )

private fun dateChipLabel(
    context: Context,
    filter: RideFilter,
): String {
    val from = filter.fromEpochMs?.let { formatFilterDate(context, rideDay(it)) }
    val to = filter.toEpochMs?.let { formatFilterDate(context, rideDay(it - 1)) }
    return when {
        from != null && to != null -> context.getString(R.string.rides_filter_chip_date_range, from, to)
        from != null -> context.getString(R.string.rides_filter_chip_date_from, from)
        else -> context.getString(R.string.rides_filter_chip_date_until, to.orEmpty())
    }
}

private fun LocalDate.startOfDayMs(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDay(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Canonical meters as the number the user sees in their own units, without a trailing ".0". */
private fun Double?.toFieldText(perUnit: Double): String {
    val value = this?.div(perUnit) ?: return ""
    return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

/** A typed number in the user's units back to canonical meters; blank or unparseable = no bound. */
private fun String.toMeters(perUnit: Double): Double? = replace(',', '.').toDoubleOrNull()?.times(perUnit)
