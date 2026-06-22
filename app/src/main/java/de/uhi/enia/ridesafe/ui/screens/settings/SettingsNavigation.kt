package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute : NavKey

fun EntryProviderScope<NavKey>.settingsEntries(
    unitSystem: UnitSystemSetting,
    onUnitSystemChange: (UnitSystemSetting) -> Unit,
) {
    entry<SettingsRoute> {
        SettingsScreen(unitSystem = unitSystem, onUnitSystemChange = onUnitSystemChange)
    }
}
