package de.uhi.enia.ridesafe.ui.components.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay

/** The card preview's height — tall enough to read a route, short enough to scroll past. */
val MapPreviewHeight = 300.dp

/**
 * How long the card waits before letting the MapView into the composition. The screens that host one
 * arrive by a navigation/pane transition, and inflating a MapView (plus rendering its markers)
 * mid-animation is main-thread work that eats the animation's frames. The wait hides behind the same
 * spinner the route-loading phase shows, so it reads as loading, not as a delay.
 * ponytail: a fixed settle delay — longer than either transition — beats plumbing "is the
 * animation done" out of two different animation systems (NavDisplay and the pane scaffold).
 */
private const val TRANSITION_SETTLE_MS = 350L

/**
 * A map in a card, framed on [framing] and drawing whatever [content] puts on it. Null [framing]
 * means the data is still loading and shows a spinner; empty means there is nothing to place, and
 * [empty] says so in the caller's own words.
 *
 * It renders in lite mode: a static snapshot, which is what a card wants and what keeps a list of
 * them cheap. Lite maps open the Google Maps app when tapped, so when [onExpand] is given the tap is
 * swallowed by an overlay and handed over instead — pair it with [FullScreenMapRequest] to open the
 * same content full-screen.
 */
@Composable
fun MapPreview(
    framing: List<LatLng>?,
    modifier: Modifier = Modifier,
    height: Dp = MapPreviewHeight,
    onExpand: (() -> Unit)? = null,
    expandLabel: String? = null,
    empty: @Composable () -> Unit = {},
    content: MapContent,
) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(TRANSITION_SETTLE_MS)
        settled = true
    }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        when {
            framing == null || !settled -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            framing.isEmpty() -> {
                empty()
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
                    MapSurface(framing = framing, liteMode = true, content = content)
                    if (onExpand != null) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clickable(onClickLabel = expandLabel, onClick = onExpand),
                        )
                    }
                }
            }
        }
    }
}
