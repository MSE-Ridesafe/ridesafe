package de.uhi.enia.ridesafe.core.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A license plate as the garage list draws it — a tight, squared-off chip so the registration
 * reads as a plate rather than as one more line of prose. One composable on purpose: the dashboard
 * header shows the same plate, and "the same format as the garage" should stay true by
 * construction, not by parallel styling.
 */
@Composable
fun LicensePlateChip(
    plate: String,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier,
    ) {
        Text(
            text = plate,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
