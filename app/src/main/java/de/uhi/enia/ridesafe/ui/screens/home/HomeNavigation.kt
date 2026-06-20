package de.uhi.enia.ridesafe.ui.screens.home

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import kotlinx.serialization.Serializable

/** Routes owned by the Home tab. Add child screens (detail/add) as more @Serializable types. */
@Serializable data object HomeGraph

@Serializable data object HomeRoute

/**
 * Home tab's nested graph — its own back stack inside the tab.
 * To add a screen: declare a route above, add a `composable<Route> { ... }` here,
 * and navigate to it from a screen lambda (back is handled by the NavHost).
 */
fun NavGraphBuilder.homeGraph(unitSystem: UnitSystemSetting) {
    navigation<HomeGraph>(startDestination = HomeRoute) {
        composable<HomeRoute> { HomeScreen(unitSystem = unitSystem) }
    }
}
