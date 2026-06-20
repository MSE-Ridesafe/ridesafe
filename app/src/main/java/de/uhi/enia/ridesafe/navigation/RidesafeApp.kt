package de.uhi.enia.ridesafe.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.screens.garage.garageGraph
import de.uhi.enia.ridesafe.ui.screens.home.HomeGraph
import de.uhi.enia.ridesafe.ui.screens.home.homeGraph
import de.uhi.enia.ridesafe.ui.screens.rides.ridesGraph
import de.uhi.enia.ridesafe.ui.screens.settings.settingsGraph
import de.uhi.enia.ridesafe.util.UnitPrefs

/**
 * App shell: adaptive navigation suite (bottom bar / rail / drawer) wrapping a
 * [NavHost]. Each tab is a nested graph with its own back stack; switching tabs
 * saves and restores that stack, so in-tab navigation context survives.
 *
 * App-level state (e.g. [UnitPrefs]) is hoisted above the NavHost so it persists
 * across every route. Per-flow shared state later belongs in nav-graph-scoped
 * ViewModels (hiltViewModel/viewModel keyed to the graph entry).
 *
 * Adding a screen: declare a route + composable in that tab's *Navigation.kt.
 * Adding a tab: new graph + an AppDestinations entry. Nothing here changes.
 */
@PreviewScreenSizes
@Composable
fun RidesafeApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    var unitSystem by rememberSaveable { mutableStateOf(UnitPrefs.get(context)) }

    val currentDestination = navController.currentBackStackEntryAsState().value?.destination

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { dest ->
                val isSelected = currentDestination?.hierarchy?.any { it.hasRoute(dest.route::class) } == true
                item(
                    icon = {
                        MaterialSymbol(
                            symbolName = dest.symbolName,
                            contentDescription = stringResource(id = dest.labelRes),
                            fill = isSelected
                        )
                    },
                    label = { Text(stringResource(id = dest.labelRes)) },
                    selected = isSelected,
                    onClick = { navController.navigateToTab(dest) }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = HomeGraph,
                modifier = Modifier.padding(innerPadding),
                // NavHost overrides animation duration to be 700ms; Restore the default native
                // animation easing curve and duration using "tween()"
                enterTransition = { fadeIn(tween()) },
                exitTransition = { fadeOut(tween()) }
            ) {
                homeGraph(unitSystem)
                ridesGraph()
                garageGraph()
                settingsGraph(
                    unitSystem = unitSystem,
                    onUnitSystemChange = { newSetting ->
                        UnitPrefs.set(context, newSetting)
                        unitSystem = newSetting
                    }
                )
            }
        }
    }
}

/** Switch top-level tabs without stacking duplicates, preserving each tab's back stack. */
private fun NavController.navigateToTab(dest: AppDestinations) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
