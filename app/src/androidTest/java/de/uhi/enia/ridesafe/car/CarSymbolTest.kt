package de.uhi.enia.ridesafe.car

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car screen's icons are Material Symbols ligatures rendered into bitmaps. If the font or its
 * ligature feature ever goes missing the name is drawn as literal text — which still "works", still
 * builds, and only shows up as "play_arrow" spelled across a button in the car. This is the check
 * that fails instead.
 */
class CarSymbolTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun symbolsRenderAsGlyphs() {
        for (symbol in listOf("play_arrow", "stop", "delete")) {
            val size = 96
            val bitmap = symbolBitmap(context, symbol, argb = 0xFFFF0000.toInt(), sizePx = size)

            var painted = 0
            var minX = size
            var maxX = -1
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (bitmap.getPixel(x, y) ushr 24 == 0) continue
                    painted++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }

            assertTrue("$symbol drew nothing", painted > 0)
            // A glyph is roughly square; the literal name would run off both edges of the square.
            assertTrue("$symbol looks like literal text, not a glyph", minX > 0 && maxX < size - 1)
        }
    }
}
