package de.uhi.enia.ridesafe.core.components.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol

/**
 * The full-screen map: the same styling and framing as [MapPreview], gesture-driven, with a floating
 * close button over it. [content] draws on it and [focus] moves its camera, so what it shows is
 * entirely the caller's business.
 *
 * [sheet] is an optional bottom sheet floating over the map — a list of what the map shows, say.
 * It covers the map rather than shrinking it, so [sheetPeek] (the sheet's own height, without the
 * navigation bar, which is added here) is kept out of the framing and out of the camera's center.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenMap(
    framing: List<LatLng>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    focus: MapFocus? = null,
    sheetPeek: Dp = 0.dp,
    sheet: (@Composable () -> Unit)? = null,
    content: MapContent,
) {
    if (sheet == null) {
        Box(modifier.fillMaxSize()) {
            MapSurface(framing = framing, liteMode = false, focus = focus, content = content)
            CloseMapButton(onClose)
        }
        return
    }

    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val peek = sheetPeek + navigationBar

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = rememberBottomSheetScaffoldState(),
        sheetPeekHeight = peek,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetShadowElevation = 6.dp,
        sheetContent = { sheet() },
    ) {
        // The scaffold's padding is deliberately ignored: the map draws behind the sheet and keeps
        // its content framed in what is left visible, via the same peek height.
        Box(Modifier.fillMaxSize()) {
            MapSurface(
                framing = framing,
                liteMode = false,
                focus = focus,
                bottomPadding = peek,
                content = content,
            )
            CloseMapButton(onClose)
        }
    }
}

/** The full-screen map's back-out control, inset clear of the status bar the map draws behind. */
@Composable
private fun BoxScope.CloseMapButton(onClose: () -> Unit) {
    IconButton(
        onClick = onClose,
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
    ) {
        MaterialSymbol(
            symbolName = "close",
            contentDescription = stringResource(R.string.ride_map_close),
            // Stated rather than inherited: the map is hosted above the app's scaffolds, where
            // LocalContentColor is still the default black rather than a surface's content color.
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
