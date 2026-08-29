package de.uhi.enia.ridesafe.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One glanceable metric: a symbol, a short label, and the value as the hero. */
class Stat(
    val icon: String,
    val label: String,
    val value: String,
)

/**
 * Widget-style stat tiles in explicit rows, ordered by importance: the first row's value renders
 * largest and each later row steps down, so a caller expresses hierarchy purely by row order
 * (hero stat alone in row one, tertiary pairs sharing a row). Corner-segmented like
 * [ListItemGroup] in both axes so the stack reads as one block. Empty rows are dropped.
 */
@Composable
fun StatGrid(
    rows: List<List<Stat>>,
    modifier: Modifier = Modifier,
) {
    val shown = rows.filter { it.isNotEmpty() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListGroupItemGap),
    ) {
        shown.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ListGroupItemGap),
            ) {
                row.forEachIndexed { colIndex, stat ->
                    StatTile(
                        stat = stat,
                        valueSize =
                            when (rowIndex) {
                                0 -> 32.sp
                                1 -> 24.sp
                                else -> 20.sp
                            },
                        shape =
                            gridItemShape(
                                top = rowIndex == 0,
                                bottom = rowIndex == shown.lastIndex,
                                start = colIndex == 0,
                                end = colIndex == row.lastIndex,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Fully rounded only where the tile forms the stack's outside corner, subtle everywhere else. */
@Composable
private fun gridItemShape(
    top: Boolean,
    bottom: Boolean,
    start: Boolean,
    end: Boolean,
): Shape {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    return RoundedCornerShape(
        topStart = (if (top && start) outer else inner).topStart,
        topEnd = (if (top && end) outer else inner).topEnd,
        bottomEnd = (if (bottom && end) outer else inner).bottomEnd,
        bottomStart = (if (bottom && start) outer else inner).bottomStart,
    )
}

@Composable
private fun StatTile(
    stat: Stat,
    valueSize: TextUnit,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceBright, modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MaterialSymbol(
                    symbolName = stat.icon,
                    contentDescription = null,
                    size = 18.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stat.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stat.value,
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontSize = valueSize,
                        lineHeight = valueSize * 1.2f,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
