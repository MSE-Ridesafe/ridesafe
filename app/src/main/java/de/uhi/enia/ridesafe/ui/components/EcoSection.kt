package de.uhi.enia.ridesafe.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R

/**
 * The dashboard's aggregated eco level (ANL-03): every profiled ride in the current dashboard
 * selection pooled into one 0–3 reading, in the same segmented-bar language as the ride detail's
 * card, so the two are recognisably one measure.
 *
 * The per-vehicle chip row that used to live here moved to the top bar's global selector — the
 * whole dashboard scopes together now. With nothing ratable in the selection the card is absent
 * (HomeScreen's gate), the same rule the safety card follows.
 */
@Composable
fun EcoSection(level: Int) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    text = stringResource(R.string.ride_detail_section_eco),
                    modifier = Modifier.weight(1f),
                )
                MaterialSymbol(
                    symbolName = "eco",
                    contentDescription = null,
                    size = 20.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            EcoLevelDisplay(level = level)
        }
    }
}
