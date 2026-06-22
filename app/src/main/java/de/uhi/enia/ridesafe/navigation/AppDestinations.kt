package de.uhi.enia.ridesafe.navigation

import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.screens.garage.GarageRoute
import de.uhi.enia.ridesafe.ui.screens.home.HomeRoute
import de.uhi.enia.ridesafe.ui.screens.rides.RidesRoute
import de.uhi.enia.ridesafe.ui.screens.settings.SettingsRoute

/**
 * Top-level destinations shown in the navigation suite. Each maps to the [route] at the
 * root of that tab's back stack; the tab stays selected for any screen pushed onto it.
 */
enum class AppDestinations(
    val labelRes: Int,
    val symbolName: String,
    val route: NavKey,
) {
    HOME(R.string.nav_home, "home", HomeRoute),
    RIDES(R.string.nav_rides, "route", RidesRoute),
    GARAGE(R.string.nav_garage, "garage_home", GarageRoute),
    SETTINGS(R.string.nav_settings, "settings", SettingsRoute),
}
