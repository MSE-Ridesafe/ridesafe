package de.uhi.enia.ridesafe.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The app's empty/none-yet state: big symbol, title, muted message, optional action below.
 * Positioning belongs to the caller's [modifier] (fill + center in a pane, top padding in a list).
 * [iconColor] is muted by default; a success-flavored state (e.g. an emptied work queue) passes
 * primary.
 */
@Composable
fun EmptyState(
    symbolName: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconColor: Color = Color.Unspecified,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MaterialSymbol(
            symbolName = symbolName,
            contentDescription = null,
            size = 64.dp,
            color = iconColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.size(16.dp))
            action()
        }
    }
}
