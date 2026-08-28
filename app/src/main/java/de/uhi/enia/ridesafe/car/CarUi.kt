package de.uhi.enia.ridesafe.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.util.inAppLanguage

/** What an action does, in the two flavours the car screen needs to tell apart at a glance. */
internal enum class CarAccent(
    val color: CarColor,
) {
    /** Get on with it: start a ride. */
    AFFIRMATIVE(CarColor.GREEN),

    /** Ends or destroys something: stop, discard, delete. */
    DESTRUCTIVE(CarColor.RED),

    /** Reading material rather than a control: let the host colour it. */
    NEUTRAL(CarColor.DEFAULT),
}

/** Rendered big and scaled down by the host, which asks for icons inside an 88 x 88 dp box. */
private const val SYMBOL_PX = 192

/**
 * How the car screens paint and speak.
 *
 * Color is a [CarColor], never a raw ARGB, and label and icon are always given the *same*
 * [CarColor] — that is the only way the two can be guaranteed to match. Per its documentation the
 * host "chooses the dark or light variant of the color when displaying the user interface,
 * depending where the color is used", so a colour baked into a bitmap cannot track what the host
 * decides for the text beside it, however carefully it is picked.
 *
 * The colours are the standard ones for the same reason: the docs call them "guaranteed to adhere
 * to the contrast requirements", while a custom colour that fails the host's contrast check is
 * dropped for a default — which is what a destructive action losing its red looks like. An app
 * colour is possible via `carColorPrimary` in an `androidx.car.app.theme`, but those are static
 * theme resources; a wallpaper-derived Material You colour cannot reach the host that way.
 *
 * Icons come from the same Material Symbols font as
 * [de.uhi.enia.ridesafe.ui.components.MaterialSymbol] and are named the same way — one icon source
 * for the whole app. The library takes an [IconCompat] rather than a composable, so the glyph is
 * drawn white into a bitmap and coloured by the host through [CarIcon.Builder.setTint], which
 * blends SRC_IN over exactly that mask.
 *
 * Strings resolve through [inAppLanguage]: the car host's context does not carry Ridesafe's own
 * language setting.
 */
internal class CarUi(
    private val carContext: CarContext,
) {
    private val icons = mutableMapOf<Pair<String, CarAccent>, CarIcon>()

    /** [symbolName] is a Material Symbols ligature, exactly as in the phone UI ("play_arrow", "stop"). */
    fun icon(
        symbolName: String,
        accent: CarAccent,
    ): CarIcon =
        icons.getOrPut(symbolName to accent) {
            CarIcon
                .Builder(IconCompat.createWithBitmap(symbolBitmap(carContext, symbolName, Color.WHITE)))
                .setTint(accent.color)
                .build()
        }

    /** [text] with [accent] on its first [length] characters — colour as emphasis, not decoration. */
    fun emphasise(
        text: CharSequence,
        accent: CarAccent,
        length: Int = text.length,
    ): CharSequence =
        SpannableString(text).apply {
            setSpan(ForegroundCarColorSpan.create(accent.color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** Re-read every time: the language can change while the car app is up. */
    fun string(
        resId: Int,
        vararg args: Any,
    ): String = carContext.inAppLanguage().getString(resId, *args)
}

/**
 * Draws a Material Symbols ligature centred on a transparent square, in [argb].
 *
 * The ligature is what turns the name into a glyph; when the font or the feature is missing the
 * name is drawn as literal text instead, which is wide, ugly and easy to miss on a car screen —
 * hence the instrumented check that the result is glyph-shaped.
 */
internal fun symbolBitmap(
    context: Context,
    symbolName: String,
    argb: Int,
    sizePx: Int = SYMBOL_PX,
): Bitmap {
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.material_symbols_outlined)
            // Same variable-font axes MaterialSymbol exposes; filled reads better at a glance.
            fontVariationSettings = "'FILL' 1, 'wght' 500, 'GRAD' 0, 'opsz' 48"
            textSize = sizePx * 0.9f
            color = argb
        }
    val bounds = Rect().also { paint.getTextBounds(symbolName, 0, symbolName.length, it) }
    return createBitmap(sizePx, sizePx).also { bitmap ->
        Canvas(bitmap).drawText(
            symbolName,
            sizePx / 2f - bounds.exactCenterX(),
            sizePx / 2f - bounds.exactCenterY(),
            paint,
        )
    }
}
