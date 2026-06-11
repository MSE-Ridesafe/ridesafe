@file:OptIn(ExperimentalTextApi::class)

package de.uhi.enia.ridesafe.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uhi.enia.ridesafe.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * A Composable that renders a Google Material Symbol by loading the font and using its ligature features.
 * Supports custom size, color, and variable font axes such as fill, weight, and grade.
 *
 * @param symbolName The ligature name of the symbol (e.g. "home", "favorite", "settings").
 * @param contentDescription The description of the symbol for accessibility. If null, the symbol is treated as decorative.
 * @param modifier The modifier to apply to the text layout.
 * @param fill True to render the filled version of the symbol.
 * @param weight The font weight (100 to 700). Default is 400.
 * @param grade The font grade (-25 to 200). Default is 0.
 * @param size The size of the symbol layout. Default is 24.dp.
 * @param color The color of the symbol. Default is LocalContentColor.current.
 */
@Composable
fun MaterialSymbol(
    symbolName: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    weight: Int = 400,
    grade: Int = 0,
    size: Dp = 24.dp,
    color: Color = LocalContentColor.current
) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            this.role = Role.Image
        }
    } else {
        Modifier.clearAndSetSemantics { }
    }

    val dynamicFontFamily = FontFamily(
        Font(
            resId = R.font.material_symbols_outlined,
            variationSettings = FontVariation.Settings(
                FontVariation.Setting("FILL", if (fill) 1f else 0f),
                FontVariation.weight(weight),
                FontVariation.Setting("GRAD", grade.toFloat()),
                FontVariation.Setting("opsz", size.value)
            )
        )
    )

    Text(
        text = symbolName,
        fontFamily = dynamicFontFamily,
        fontSize = size.value.sp,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier.then(semanticsModifier)
    )
}
