package de.uhi.enia.ridesafe.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What the detail pane shows on a two-pane layout before anything is selected. Never seen on a
 * phone, where the list occupies the whole window until a detail is pushed over it.
 */
@Composable
fun DetailPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Wraps a list pane's content behind a focus sink.
 *
 * The pane scaffold focuses whichever pane it just navigated to. On a detail pane that is welcome —
 * a form lands on its first field — but on a list pane focus search takes the first focusable: the
 * first row picks up a focus tint nobody asked for, and on the rides list that focusable is the
 * search field, which drags the IME up on every visit to the tab.
 *
 * The sink is simply the first thing focus search can find, so the scaffold parks focus on an inert
 * 1dp box instead. Nothing here touches focus *properties* or pointer input, so tapping the search
 * field still focuses it and still opens the keyboard — which is what a
 * `focusProperties { enter = FocusRequester.Cancel }` guard got wrong, since that swallows the focus
 * a tap asks for too. It is also indifferent to *when* the scaffold asks, unlike clearing focus
 * after the fact: that races the pane animation and loses.
 */
@Composable
fun ListPaneFocusSink(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Spacer(Modifier.size(1.dp).focusable())
        content()
    }
}
