package de.uhi.enia.ridesafe.navigation

import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.screens.garage.GarageGraph
import de.uhi.enia.ridesafe.ui.screens.home.HomeGraph
import de.uhi.enia.ridesafe.ui.screens.rides.RidesGraph
import de.uhi.enia.ridesafe.ui.screens.settings.SettingsGraph

/**
 * Top-level destinations shown in the navigation suite. Each maps to a tab's
 * nested graph [route]; the tab stays selected for any screen within that graph.
 */
enum class AppDestinations(
    val labelRes: Int,
    val symbolName: String,
    val route: Any,
) {
    HOME(R.string.nav_home, "home", HomeGraph),
    RIDES(R.string.nav_rides, "route", RidesGraph),
    GARAGE(R.string.nav_garage, "garage_home", GarageGraph),
    SETTINGS(R.string.nav_settings, "settings", SettingsGraph),
}
