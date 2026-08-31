package de.uhi.enia.ridesafe.feature.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Whether the first-launch onboarding (ONB-01) is done, plus the transient replay request
 * from Settings (ONB-07). Same snapshot-state-backed prefs idiom as
 * [de.uhi.enia.ridesafe.core.preferences.ThemePrefs], so MainActivity's gate recomposes on change.
 *
 * [replayRequested] is deliberately not persisted, and replaying does not clear the stored
 * flag: the first-run check marks the flag done when the garage already has vehicles, so
 * clearing it to replay would cancel the replay on the spot. Not persisting also means a
 * replay abandoned by killing the app stays gone.
 */
object OnboardingPrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_DONE = "onboarding_done"

    private var cached by mutableStateOf<Boolean?>(null)

    var replayRequested by mutableStateOf(false)

    fun isCompleted(context: Context): Boolean =
        cached ?: context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)
            .also { cached = it }

    fun setCompleted(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DONE, true) }
        cached = true
        replayRequested = false
    }
}

/**
 * First-run decision, kept free of Android types so the truth table is unit-testable: show the
 * wizard only on a fresh install (ONB-01). An existing install updating into this feature has
 * vehicles already — suppress and mark done, so the wizard never nags a set-up user.
 */
enum class FirstRunDecision {
    SHOW,
    SUPPRESS_AND_MARK_DONE,
    SKIP,
}

fun firstRunDecision(
    completed: Boolean,
    vehicleCount: Int,
): FirstRunDecision =
    when {
        completed -> FirstRunDecision.SKIP
        vehicleCount > 0 -> FirstRunDecision.SUPPRESS_AND_MARK_DONE
        else -> FirstRunDecision.SHOW
    }
