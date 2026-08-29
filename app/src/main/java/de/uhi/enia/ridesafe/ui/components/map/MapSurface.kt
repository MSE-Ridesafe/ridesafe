package de.uhi.enia.ridesafe.ui.components.map

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.theme.resolvedDarkTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a map draws: markers, lines and shapes. Named rather than written inline at each call site
 * because ktlint's annotation rule and its function-type-modifier-spacing rule disagree about how to
 * format an annotated function type in a parameter list, and reformat each other's output forever.
 */
@Suppress("ktlint:standard:function-type-modifier-spacing")
typealias MapContent =
    @Composable @GoogleMapComposable
    () -> Unit

/**
 * Where a map should move when something outside it takes the camera over — a picked list row, a
 * geocoded address. [tick] counts the requests so asking for the same target twice still moves the
 * camera back to it; [zoom] is a floor, never a zoom-out.
 */
data class MapFocus(
    val target: LatLng,
    val zoom: Float = 17f,
    val tick: Int = 0,
)

/** Map dp per world at zoom 0, and the margin kept between the framed points and the map's edges. */
private const val WORLD_DP = 256.0
private const val MAP_PADDING_DP = 32.0

/** Web Mercator's y for a latitude, as used for the zoom that fits a bounding box. */
private fun mercatorY(latitude: Double): Double {
    val sin = sin(latitude * PI / 180).coerceIn(-0.9999, 0.9999)
    return ln((1 + sin) / (1 - sin)) / 2
}

/** The latitude at a Web Mercator y — [mercatorY] backwards. */
private fun latitudeAt(mercator: Double): Double = atan(sinh(mercator)) * 180 / PI

/**
 * The middle of [bounds] *as the map draws it*. LatLngBounds.center averages the latitudes, but a map
 * spaces them out by Mercator, so on anything but an east-west box the two differ — and since the fit
 * below leaves only [MAP_PADDING_DP] of slack, centering on the wrong one crops an edge off the very
 * points that were supposed to fit. North-south routes suffer most; east-west ones hide the bug.
 */
private fun mercatorCenter(bounds: LatLngBounds): LatLng {
    val y = (mercatorY(bounds.northeast.latitude) + mercatorY(bounds.southwest.latitude)) / 2
    return LatLng(latitudeAt(y), bounds.center.longitude)
}

/**
 * The camera that frames [points] in a [widthDp] × [heightDp] map, computed before the map exists.
 * This is what CameraUpdateFactory.newLatLngBounds works out internally, done up front so the
 * camera can be handed to GoogleMapOptions: a map born at the right place never shows the
 * world view → initial position → fitted route sequence that moving the camera after load gives.
 */
private fun fittingCamera(
    points: List<LatLng>,
    widthDp: Float,
    heightDp: Float,
): CameraPosition {
    val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
    if (points.size < 2) return CameraPosition.fromLatLngZoom(bounds.center, 14f)
    val center = mercatorCenter(bounds)
    val latFraction = (mercatorY(bounds.northeast.latitude) - mercatorY(bounds.southwest.latitude)) / (2 * PI)
    val lngSpan = (bounds.northeast.longitude - bounds.southwest.longitude).let { if (it < 0) it + 360 else it }
    val zoom =
        minOf(
            log2((heightDp - 2 * MAP_PADDING_DP).coerceAtLeast(1.0) / WORLD_DP / latFraction),
            log2((widthDp - 2 * MAP_PADDING_DP).coerceAtLeast(1.0) / WORLD_DP / (lngSpan / 360)),
        )
    // Points with no extent at all divide by zero above; the coercion turns that into street zoom.
    return CameraPosition.fromLatLngZoom(center, zoom.coerceIn(2.0, 18.0).toFloat())
}

/**
 * A Google Map framed on [framing] and styled like the rest of the app, whatever it draws: [content]
 * is a normal map-content lambda, so callers bring their own markers, lines and shapes.
 *
 * [liteMode] true renders a static snapshot (the preview); false is a live, gesture-driven map.
 * Gestures are kept 2D — pan/zoom/rotate on, tilt off — and the toolbar is hidden so taps stay
 * in-app rather than launching the Maps app.
 *
 * Dark mode is a JSON style rather than GoogleMap's mapColorScheme: lite mode ignores the color
 * scheme but does honor JSON styling, and one mechanism keeps every map looking the same. A style
 * can only be set after the map is created, so the map stays covered until it reports itself
 * loaded — otherwise it shows a light frame or two before the style lands.
 *
 * [onLoaded] hoists that cover: non-null suppresses the internal one and instead reports the same
 * moment (map loaded, or the reveal fallback) upward, so a caller with waits of its own — MapPreview
 * loads the route and sits out the open transition first — can keep one continuous spinner across
 * every phase instead of mounting a second one when the map joins the composition.
 *
 * [bottomPadding] is how much of the map something else covers (a sheet), kept out of both the
 * framing and the camera's idea of center.
 */
@Composable
internal fun MapSurface(
    framing: List<LatLng>,
    liteMode: Boolean,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    focus: MapFocus? = null,
    onLoaded: (() -> Unit)? = null,
    content: MapContent,
) {
    var mapLoaded by remember { mutableStateOf(false) }

    fun markLoaded() {
        if (!mapLoaded) {
            mapLoaded = true
            onLoaded?.invoke()
        }
    }
    val dark = resolvedDarkTheme()
    val context = LocalContext.current
    val mapStyle =
        remember(dark) {
            MapStyleOptions.loadRawResourceStyle(context, if (dark) R.raw.map_style_night else R.raw.map_style_day)
        }
    val mapBackground = MaterialTheme.colorScheme.surfaceBright.toArgb()

    // The map renders into a TextureView, which composites with whatever is drawn behind it in the
    // same hierarchy. Without an opaque layer there, everything the map draws with alpha — water,
    // rail, whole frames mid-zoom — blends with the screen behind the map instead. Same color as
    // the loading cover below, so the fade between them shows no shift.
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceBright)) {
        val camera =
            remember(framing, maxWidth, maxHeight, bottomPadding) {
                fittingCamera(framing, maxWidth.value, (maxHeight - bottomPadding).value)
            }
        val cameraPositionState = rememberCameraPositionState { position = camera }

        // Move to whatever took the camera over, close enough to read it. Zoom only ever goes in:
        // the map opens framed on everything, and a pan alone leaves the target one dot among many.
        LaunchedEffect(focus) {
            val target = focus ?: return@LaunchedEffect
            val zoom = maxOf(cameraPositionState.position.zoom, target.zoom)
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target.target, zoom), 400)
        }

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            googleMapOptionsFactory = { GoogleMapOptions().liteMode(liteMode).camera(camera).backgroundColor(mapBackground) },
            contentPadding = PaddingValues(bottom = bottomPadding),
            properties = MapProperties(mapStyleOptions = mapStyle),
            uiSettings =
                MapUiSettings(
                    tiltGesturesEnabled = false,
                    mapToolbarEnabled = false,
                    zoomControlsEnabled = false,
                ),
            onMapLoaded = ::markLoaded,
            content = content,
        )

        // The same placeholder a caller shows while its data loads, so the two phases read as one
        // wait rather than a spinner followed by a flashing map. It fades rather than pops, which
        // also hides the frame or two the map spends applying its style. Skipped when the caller
        // hoisted the cover via [onLoaded] — theirs is already on top.
        if (onLoaded == null) {
            val coverAlpha by animateFloatAsState(if (mapLoaded) 0f else 1f, tween(400), label = "mapCover")
            if (coverAlpha > 0f) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .alpha(coverAlpha)
                            .background(MaterialTheme.colorScheme.surfaceBright),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }

    // Reveal the map anyway if it never reports itself loaded, so this can't sit on a spinner.
    // Long enough not to pre-empt a slow first load, which reveals a half-tiled map.
    LaunchedEffect(Unit) {
        delay(6_000.milliseconds)
        markLoaded()
    }
}
