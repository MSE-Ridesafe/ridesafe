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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.RideEco
import de.uhi.enia.ridesafe.rides.processing.score.ecoLevel
import de.uhi.enia.ridesafe.ui.components.EcoLevelDisplay
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import kotlin.math.roundToInt

/** One regime's slice of the ride's time: its label, its colour, and the seconds spent in it. */
private data class EcoBucket(
    val label: String,
    val color: Color,
    val seconds: Double,
)

/**
 * A ride's efficiency (ANL-03): the eco level as a three-segment bar with an encouraging one-liner,
 * and — visually its own section, below a divider — where the ride's time went, as one labelled
 * track bar per regime.
 *
 * Deliberately no numbers anywhere: an absolute figure from phone sensors would be pseudo-precision
 * (which is why the litres estimate this replaced was dropped), and the safety score next door
 * already owns gauges and 0–100. Three segments that fill is a different visual language for a
 * deliberately coarser claim. Exact percentages live only in accessibility descriptions, where a
 * screen reader needs them.
 *
 * The breakdown is track bars rather than a second segmented strip on purpose: two segmented bars
 * stacked read as one mechanism when they are two — one is a rating, the other a composition. The
 * subtitle coaches rather than scolds, because a driver told off by their own logbook stops opening
 * it; even the bottom level frames the gap as headroom.
 *
 * [eco] is the ride's stored profile; the caller renders nothing when it is null. The level can
 * still be null for a profile with too little driving to judge — then the card shows only the
 * breakdown, since the regime split is honest at any length.
 */
@Composable
fun EcoCard(
    eco: RideEco,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val level = ecoLevel(eco)
    val buckets =
        listOf(
            EcoBucket(stringResource(R.string.ride_eco_idle), scheme.error, eco.idleSeconds),
            EcoBucket(stringResource(R.string.ride_eco_accelerating), scheme.tertiary, eco.accelSeconds),
            EcoBucket(stringResource(R.string.ride_eco_cruising), scheme.primary, eco.cruiseSeconds),
            EcoBucket(stringResource(R.string.ride_eco_braking), scheme.secondary, eco.brakeSeconds),
        ).filter { it.seconds > 0 }
    val totalSeconds = buckets.sumOf { it.seconds }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceBright),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ride_detail_section_eco),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                MaterialSymbol(
                    symbolName = "eco",
                    contentDescription = null,
                    size = 20.dp,
                    color = scheme.onSurfaceVariant,
                )
            }

            if (level != null) {
                EcoLevelDisplay(level = level)
            }

            if (buckets.isNotEmpty() && totalSeconds > 0) {
                HorizontalDivider(color = scheme.surfaceContainerHighest)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.ride_eco_breakdown_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    // One width for every label so the tracks share a left edge — shares are only
                    // comparable across rows when the bars start at the same x (see JourneyTimeline,
                    // which sizes its timestamp column the same way).
                    val measurer = rememberTextMeasurer()
                    val labelStyle = MaterialTheme.typography.bodyMedium
                    val labelWidth =
                        with(LocalDensity.current) {
                            buckets.maxOf { measurer.measure(it.label, labelStyle).size.width }.toDp() + 2.dp
                        }
                    buckets.forEach { bucket ->
                        EcoRegimeRow(bucket = bucket, share = bucket.seconds / totalSeconds, labelWidth = labelWidth)
                    }
                }
            }

            Text(
                text = stringResource(R.string.ride_eco_note),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Track height, and the fill floor keeping a tiny-but-real share from rendering as nothing. */
private val EcoTrackHeight = 8.dp
private const val MIN_VISIBLE_SHARE = 0.02f

/**
 * One regime as a labelled track bar: the label in a shared-width column, then a track whose fill
 * is the regime's share of the ride's time. Deliberately not another segmented strip — this is a
 * composition, not a rating, and the two must not look like the same control.
 */
@Composable
private fun EcoRegimeRow(
    bucket: EcoBucket,
    share: Double,
    labelWidth: Dp,
) {
    // The visible row is wordless beyond its label; the share a sighted user reads off the fill is
    // spoken here instead, since a screen reader gets nothing from proportional widths.
    val description = stringResource(R.string.ride_eco_bucket_a11y, bucket.label, (share * 100).roundToInt())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            text = bucket.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(labelWidth),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(EcoTrackHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(share.toFloat().coerceAtLeast(MIN_VISIBLE_SHARE))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(bucket.color),
            )
        }
    }
}
