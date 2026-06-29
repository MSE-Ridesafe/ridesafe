package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.tracking.AutoTrackMode
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute : NavKey

@Serializable data object SettingsLanguageRoute : NavKey

@Serializable data object SettingsUnitsRoute : NavKey

@Serializable data object SettingsAutoTrackRoute : NavKey

fun EntryProviderScope<NavKey>.settingsEntries(
    unitSystem: UnitSystemSetting,
    onUnitSystemChange: (UnitSystemSetting) -> Unit,
    autoTrackMode: AutoTrackMode,
    onAutoTrackModeChange: (AutoTrackMode) -> Unit,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
) {
    entry<SettingsRoute> {
        SettingsScreen(
            unitSystem = unitSystem,
            autoTrackMode = autoTrackMode,
            onOpenLanguage = { onOpen(SettingsLanguageRoute) },
            onOpenUnits = { onOpen(SettingsUnitsRoute) },
            onOpenAutoTrack = { onOpen(SettingsAutoTrackRoute) },
        )
    }
    entry<SettingsLanguageRoute> {
        LanguageSettingsScreen(onBack = onBack)
    }
    entry<SettingsUnitsRoute> {
        UnitSettingsScreen(
            unitSystem = unitSystem,
            onUnitSystemChange = onUnitSystemChange,
            onBack = onBack,
        )
    }
    entry<SettingsAutoTrackRoute> {
        AutoTrackSettingsScreen(
            autoTrackMode = autoTrackMode,
            onAutoTrackModeChange = onAutoTrackModeChange,
            onBack = onBack,
        )
    }
}
