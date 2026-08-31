package de.uhi.enia.ridesafe.core.components.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Something to show full-screen over the whole app: [content] gets the way to close itself and is
 * expected to be (or contain) a [FullScreenMap].
 *
 * It is hosted at the app's root, above the navigation bar, because such a map cannot live in a
 * Dialog: the Maps SDK leaves some features (built-up areas, rail, minor roads) partly transparent,
 * and a dialog's own window lets the compositor blend that alpha over the screen behind it. The
 * activity's window is opaque, so the same alpha has nothing to bleed into.
 */
class FullScreenMapRequest(
    val content: @Composable (onClose: () -> Unit) -> Unit,
)

/** Where a screen publishes its request; [FullScreenMapHost] provides the state and renders it. */
val LocalFullScreenMap = compositionLocalOf { mutableStateOf<FullScreenMapRequest?>(null) }

/**
 * Renders whatever [state] holds, fading it in and out and closing it on back. Put this at the app
 * root, as the last child of a box that covers everything including the navigation bar.
 */
@Composable
fun FullScreenMapHost(state: MutableState<FullScreenMapRequest?>) {
    val request = state.value
    // The content has to outlive the request: reading the state inside the animation would empty it
    // the moment it is cleared, and the fade would play over nothing — a hard cut, not a transition.
    var showing by remember { mutableStateOf<FullScreenMapRequest?>(null) }
    LaunchedEffect(request) { if (request != null) showing = request }
    if (request != null) BackHandler { state.value = null }

    AnimatedVisibility(visible = request != null, enter = fadeIn(), exit = fadeOut()) {
        showing?.content { state.value = null }
    }
}
