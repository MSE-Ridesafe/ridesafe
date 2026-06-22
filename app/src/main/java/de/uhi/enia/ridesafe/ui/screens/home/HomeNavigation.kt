package de.uhi.enia.ridesafe.ui.screens.home

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey

/** Home tab entries. Add child screens by declaring a @Serializable NavKey and an entry<Route> here. */
fun EntryProviderScope<NavKey>.homeEntries(unitSystem: UnitSystemSetting) {
    entry<HomeRoute> { HomeScreen(unitSystem = unitSystem) }
}
