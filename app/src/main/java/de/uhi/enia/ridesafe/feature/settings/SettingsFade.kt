package de.uhi.enia.ridesafe.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay

/**
 * Material fade-through for settings that restyle the whole app — units, currency, and language.
 *
 * Both are read straight from their source wherever they are used, so every label on screen flips
 * the instant the setting is written. Applying the write while the UI is invisible turns that hard
 * cut into a fade, the way the system Settings app switches language.
 *
 * A [androidx.compose.animation.Crossfade] cannot do this: its outgoing copy would read the same
 * setting as the incoming one and already show the new value, leaving nothing to fade between.
 *
 * Process-global because the fade outlives the activity: [applyAcrossRestart] hands the second
 * half of the animation to whichever activity comes back.
 */
object SettingsFade {
    private const val FADE_OUT_MILLIS = 90
    private const val FADE_IN_MILLIS = 210

    private val animatable = Animatable(1f)
    private var pendingFadeIn = false

    /** Read from a `graphicsLayer` block, so a change only redraws — it never recomposes. */
    val alpha: Float get() = animatable.value

    /** Fades out, runs [apply] while invisible, fades back in. For changes the activity survives. */
    suspend fun applyWhileHidden(apply: () -> Unit) {
        animatable.animateTo(0f, tween(FADE_OUT_MILLIS))
        apply()
        animatable.animateTo(1f, tween(FADE_IN_MILLIS))
    }

    /**
     * Same fade for a change that recreates the activity — setting the app locale always does,
     * `configChanges="locale"` included (measured: the activity relaunches regardless). The new
     * activity picks the fade up in [resumeFadeIn].
     *
     * The relaunch itself punches ~4 frames of black through the middle that the app cannot paint
     * over — unlike a night-mode recreation, which the system crossfades for us. Fading the
     * content out first is what keeps that from reading as a hard cut.
     */
    suspend fun applyAcrossRestart(apply: () -> Unit) {
        animatable.animateTo(0f, tween(FADE_OUT_MILLIS))
        pendingFadeIn = true
        apply()
        // The restart cancels this coroutine before the delay elapses. If it somehow does not,
        // recover rather than leaving the app invisible.
        delay(timeMillis = 1000)
        pendingFadeIn = false
        animatable.animateTo(1f, tween(FADE_IN_MILLIS))
    }

    /** Called once per activity: completes a fade that [applyAcrossRestart] left half-done. */
    suspend fun resumeFadeIn() {
        if (!pendingFadeIn) return
        pendingFadeIn = false
        animatable.animateTo(1f, tween(FADE_IN_MILLIS))
    }
}
