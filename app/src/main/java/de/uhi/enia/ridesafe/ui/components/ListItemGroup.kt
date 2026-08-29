package de.uhi.enia.ridesafe.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Vertical gap between adjacent items of a grouped list (the system Settings app look). */
val ListGroupItemGap = 2.dp

/**
 * One item of an Android-settings-style grouped list: its own surface, fully rounded at the
 * group's ends and subtly rounded towards its neighbours. Stack items [ListGroupItemGap] apart —
 * or use [ListItemGroup], which does both when the rows are statically known.
 */
@Composable
fun ListGroupItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    Surface(
        shape = RoundedCornerShape(top.topStart, top.topEnd, bottom.bottomEnd, bottom.bottomStart),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/** A whole grouped list at once, for statically known rows. */
@Composable
fun ListItemGroup(
    vararg items: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ListGroupItemGap)) {
        items.forEachIndexed { index, item -> ListGroupItem(index = index, count = items.size) { item() } }
    }
}
