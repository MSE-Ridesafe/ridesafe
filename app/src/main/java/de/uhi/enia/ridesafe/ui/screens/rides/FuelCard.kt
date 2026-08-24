package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.RideFuel
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import kotlin.math.roundToInt

/** One regime's slice of the ride's fuel: its label, its colour, and how many litres went into it. */
private data class FuelBucket(
    val label: String,
    val color: Color,
    val liters: Double,
)

/**
 * A ride's estimated fuel consumption (ANL-03): the total, the resulting L/100 km, and a bar
 * splitting the fuel across the driving regimes it was burned in.
 *
 * The split is the part worth showing. Total litres only restates the distance, whereas "a fifth of
 * this tank went while standing still" is the stop-and-go insight the requirement actually asks for,
 * and it is what a driver can do something about.
 *
 * [fuel] is already calibrated for the ride's vehicle (see RideFuel.forVehicle); the caller renders
 * nothing when there is no estimate to show. [distanceMeters] is null for a ride whose distance
 * hasn't been derived yet, which only costs the L/100 km line. [calibrated] false means no rated
 * economy is on file and the figures are the model's generic vehicle, which the card says out loud
 * rather than passing off as the user's car.
 */
@Composable
fun FuelCard(
    fuel: RideFuel,
    distanceMeters: Double?,
    calibrated: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val buckets =
        listOf(
            FuelBucket(stringResource(R.string.ride_fuel_idle), scheme.error, fuel.idleLiters),
            FuelBucket(stringResource(R.string.ride_fuel_accelerating), scheme.tertiary, fuel.accelLiters),
            FuelBucket(stringResource(R.string.ride_fuel_cruising), scheme.primary, fuel.cruiseLiters),
            FuelBucket(stringResource(R.string.ride_fuel_slowing), scheme.secondary, fuel.decelLiters),
        )
    val total = fuel.totalLiters

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceBright),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ride_detail_section_fuel),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                MaterialSymbol(
                    symbolName = "local_gas_station",
                    contentDescription = null,
                    size = 20.dp,
                    color = scheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = stringResource(R.string.ride_fuel_liters, formatLiters(total)),
                    style = MaterialTheme.typography.headlineMedium,
                    color = scheme.onSurface,
                )
                // Only meaningful once the route pass has filled the distance in; until then the
                // litres stand on their own rather than being divided by nothing.
                if (distanceMeters != null && distanceMeters > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.ride_fuel_consumption, formatLiters(total / distanceMeters * 100_000.0)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }

            FuelBar(buckets = buckets, total = total)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                buckets.filter { it.liters > 0 }.forEach { bucket ->
                    FuelLegendRow(bucket = bucket, share = bucket.liters / total)
                }
            }

            Text(
                text =
                    stringResource(R.string.ride_fuel_estimate_note) +
                        if (calibrated) "" else " " + stringResource(R.string.ride_fuel_calibration_hint),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Bar height, and the width below which a slice is widened so it stays visible as a sliver. */
private val FuelBarHeight = 12.dp
private const val MIN_VISIBLE_SHARE = 0.02f

/**
 * The ride's fuel as one bar, each regime a proportional slice.
 *
 * Weights rather than measured widths, so the slices always add up to the bar exactly. A slice below
 * [MIN_VISIBLE_SHARE] is floored to it: a 0.4 % slice rounds to nothing on screen, and "nothing" and
 * "none" must not look the same when the legend underneath lists it. That distorts the bar by at
 * most a couple of percent, which a bar this size cannot convey anyway — the litres in the legend
 * are the exact figures.
 */
@Composable
private fun FuelBar(
    buckets: List<FuelBucket>,
    total: Double,
) {
    if (total <= 0) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(FuelBarHeight)
                .clip(CircleShape)
                // Decorative: every slice is named with its litres and share in the legend below.
                .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        buckets
            .filter { it.liters > 0 }
            .forEach { bucket ->
                Box(
                    modifier =
                        Modifier
                            .weight((bucket.liters / total).toFloat().coerceAtLeast(MIN_VISIBLE_SHARE))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(bucket.color),
                )
            }
    }
}

@Composable
private fun FuelLegendRow(
    bucket: FuelBucket,
    share: Double,
) {
    val detail =
        stringResource(
            R.string.ride_fuel_bucket_detail,
            formatLiters(bucket.liters),
            (share * 100).roundToInt(),
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = "${bucket.label}: $detail" },
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(bucket.color),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = bucket.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Litres to one decimal.
 *
 * ponytail: litres and L/100 km regardless of the unit setting, which is what the Garage already
 * does for tank size and rated economy — a fuel figure in gallons next to a rated economy in
 * L/100 km would be worse than the current consistency. Converting volumes is SET-08's job, and it
 * has to happen everywhere at once when it lands.
 */
private fun formatLiters(liters: Double): String = "%.1f".format(liters)
