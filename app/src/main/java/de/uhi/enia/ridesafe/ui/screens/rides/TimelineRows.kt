@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatDurationMs
import de.uhi.enia.ridesafe.util.formatTimeOfDay
import de.uhi.enia.ridesafe.util.formattingLocale
import java.text.NumberFormat

@Composable
internal fun LogbookRow(
    entry: LogbookEntry,
    selectionMode: Boolean,
    selected: Boolean,
    isOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current

    val overline: String?
    val headline: String
    val supporting: String
    val icon: String
    when (entry) {
        is LogbookEntry.Single -> {
            val ride = entry.row.ride
            overline = entry.row.vehicleName
            // Prefer a matched saved place's label + icon (ADR-08), else the raw destination address.
            headline =
                entry.row.endPlace?.label
                    ?: ride.endAddress?.let { shortAddress(it) }
                    ?: stringResource(R.string.ride_address_unknown)
            supporting =
                listOfNotNull(
                    rideTimeRange(context, ride.startedAtEpochMs, ride.endedAtEpochMs),
                    formatDuration(ride.startedAtEpochMs, ride.endedAtEpochMs),
                ).joinToString("  •  ")
            icon = entry.row.endPlace?.icon ?: "route"
        }

        is LogbookEntry.Merged -> {
            val s = entry.summary
            // The merged trip's final destination = the newest stop's end (stops are oldest-first).
            val destPlace = entry.stops.last().endPlace
            val badge =
                stringResource(R.string.ride_merged_label) + " · " +
                    pluralStringResource(R.plurals.ride_stops_count, s.stopCount, s.stopCount)
            overline = listOfNotNull(entry.vehicleName, badge).joinToString(" · ")
            headline =
                destPlace?.label
                    ?: s.endAddress?.let { shortAddress(it) }
                    ?: stringResource(R.string.ride_address_unknown)
            supporting =
                listOfNotNull(
                    s.distanceMeters?.let { formatDistance(it, unitSystem) },
                    formatDurationMs(s.movingDurationMs),
                ).joinToString("  •  ")
            // Keep the "merge" icon so a merged trip stays visually distinct in the list.
            icon = "merge"
        }
    }

    ListItem(
        modifier =
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors =
            ListItemDefaults.colors(
                containerColor =
                    if (isOpen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
        leadingContent = {
            if (selectionMode) {
                SelectionCircle(selected = selected)
            } else {
                MaterialSymbol(symbolName = icon, contentDescription = null)
            }
        },
        overlineContent = overline?.let { { Text(it) } },
        headlineContent = {
            Text(
                headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent =
            if (selectionMode) {
                null
            } else {
                {
                    MaterialSymbol(
                        symbolName = "chevron_right",
                        contentDescription = stringResource(R.string.ride_open),
                    )
                }
            },
    )
}

@Composable
internal fun RefuelTimelineRow(
    row: RefuelRow,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    nested: Boolean = false,
    showVehicle: Boolean = true,
    // True while this refuel's editor fills the detail pane, mirroring LogbookRow's tint.
    isOpen: Boolean = false,
) {
    val context = LocalContext.current
    // Regional conventions, not the in-app language's likely region (SET-07).
    val locale = formattingLocale()
    val refuel = row.refuel
    val currency = refuelCurrency(refuel.currencyCode, locale)
    val fractionDigits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: 2
    val total = java.math.BigDecimal.valueOf(refuel.totalPriceMinor, fractionDigits)
    val currencyFormat = NumberFormat.getCurrencyInstance(locale).apply { this.currency = currency }
    val unitPrice =
        pricePerLiter(refuel.totalPriceMinor, refuel.fuelAmountMilliliters, fractionDigits)
            ?.let(currencyFormat::format)

    ListItem(
        modifier =
            Modifier
                .then(if (nested) Modifier.padding(start = 24.dp) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors =
            ListItemDefaults.colors(
                containerColor =
                    if (isOpen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
        leadingContent = {
            if (selectionMode) {
                SelectionCircle(selected)
            } else {
                MaterialSymbol(symbolName = "local_gas_station", contentDescription = null)
            }
        },
        overlineContent =
            if (showVehicle) {
                { Text(row.vehicleName ?: stringResource(R.string.refuel_unknown_vehicle)) }
            } else {
                null
            },
        headlineContent = {
            Text(
                stringResource(R.string.refuel_label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                buildList {
                    add(formatTimeOfDay(context, refuel.timestampEpochMs))
                    add(currencyFormat.format(total))
                    unitPrice?.let { add(stringResource(R.string.refuel_price_per_liter_value, it)) }
                }.joinToString("  •  "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** The native Android selection indicator: a filled primary check when selected, an empty circle otherwise. */
@Composable
private fun SelectionCircle(selected: Boolean) {
    MaterialSymbol(
        symbolName = if (selected) "check_circle" else "radio_button_unchecked",
        contentDescription = stringResource(if (selected) R.string.ride_selected else R.string.ride_not_selected),
        fill = selected,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "14:32 – 14:58" (or just the start while a ride is in progress). */
private fun rideTimeRange(
    context: android.content.Context,
    startMs: Long,
    endMs: Long?,
): String =
    buildString {
        append(formatTimeOfDay(context, startMs))
        endMs?.let { append(" – ").append(formatTimeOfDay(context, it)) }
    }
