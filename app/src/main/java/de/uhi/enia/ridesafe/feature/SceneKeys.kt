package de.uhi.enia.ridesafe.feature

/**
 * Scene keys shared across feature boundaries.
 *
 * A key identifies one adaptive list-detail scene, so every entry that should share a pane pair has
 * to name the same one. [SETTINGS_SCENE] lives here rather than in `feature.settings` because the
 * saved-place screens render in the Settings tab's detail pane while owning their own feature —
 * holding it in either feature would make the two import each other.
 *
 * The Logbook's and Garage's own keys stay private to those features: nothing outside them
 * registers an entry into their scenes.
 */
internal const val SETTINGS_SCENE = "settings"
