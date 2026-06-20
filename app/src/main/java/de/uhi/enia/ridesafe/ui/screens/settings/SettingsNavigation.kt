package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

@Serializable data object SettingsGraph

@Serializable data object SettingsRoute

fun NavGraphBuilder.settingsGraph(
    unitSystem: UnitSystemSetting,
    onUnitSystemChange: (UnitSystemSetting) -> Unit,
) {
    navigation<SettingsGraph>(startDestination = SettingsRoute) {
        composable<SettingsRoute> {
            SettingsScreen(unitSystem = unitSystem, onUnitSystemChange = onUnitSystemChange)
        }
    }
}
