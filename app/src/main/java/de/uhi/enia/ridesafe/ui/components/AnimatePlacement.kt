package de.uhi.enia.ridesafe.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import kotlinx.coroutines.launch

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
fun Modifier.animatePlacement(): Modifier {
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
