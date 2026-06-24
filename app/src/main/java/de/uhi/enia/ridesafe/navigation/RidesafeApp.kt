package de.uhi.enia.ridesafe.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import de.uhi.enia.ridesafe.tracking.AutoTrackPrefs
import de.uhi.enia.ridesafe.tracking.applyAutoTrackMode
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.screens.garage.GarageRoute
import de.uhi.enia.ridesafe.ui.screens.garage.GarageViewModel
import de.uhi.enia.ridesafe.ui.screens.garage.garageEntries
import de.uhi.enia.ridesafe.ui.screens.home.HomeRoute
import de.uhi.enia.ridesafe.ui.screens.home.homeEntries
import de.uhi.enia.ridesafe.ui.screens.rides.RidesRoute
import de.uhi.enia.ridesafe.ui.screens.rides.RidesViewModel
import de.uhi.enia.ridesafe.ui.screens.rides.ridesEntries
import de.uhi.enia.ridesafe.ui.screens.settings.SettingsRoute
import de.uhi.enia.ridesafe.ui.screens.settings.settingsEntries
import de.uhi.enia.ridesafe.util.UnitPrefs

// ponytail: animation durations are tuning knobs — bump if a transition feels off.
private const val SLIDE_MS = 250 // sub-route slide + matching fade-out of the previous screen
private const val FADE_MS = 100 // quick cross-fade between tabs

/**
 * App shell: adaptive navigation suite (bottom bar / rail / drawer) wrapping a
 * [NavDisplay]. Each tab owns a [rememberNavBackStack]; the selected tab decides which
 * stack [NavDisplay] renders, so switching tabs preserves each tab's in-tab navigation.
 * NavDisplay supplies the native default transitions and predictive-back animation.
 *
 * App-level state (e.g. [UnitPrefs]) is hoisted above the display so it persists across
 * every route. The garage flow's [GarageViewModel] is hoisted here too (one app-scoped
 * instance shared by its three screens), since Nav3 has no graph scope.
 *
 * Adding a screen: declare a @Serializable NavKey + an entry in that tab's *Navigation.kt
 * and push it onto the tab's back stack. Adding a tab: new root route + entry builder + an
 * AppDestinations entry + a back stack below.
 */
@PreviewScreenSizes
@Composable
fun RidesafeApp() {
    val context = LocalContext.current
    var unitSystem by rememberSaveable { mutableStateOf(UnitPrefs.get(context)) }
    var autoTrackMode by rememberSaveable { mutableStateOf(AutoTrackPrefs.get(context)) }

    // One back stack per tab; the active tab selects which one NavDisplay renders.
    val homeStack = rememberNavBackStack(HomeRoute)
    val ridesStack = rememberNavBackStack(RidesRoute)
    val garageStack = rememberNavBackStack(GarageRoute)
    val settingsStack = rememberNavBackStack(SettingsRoute)
    val stacks =
        remember {
            mapOf(
                AppDestinations.HOME to homeStack,
                AppDestinations.RIDES to ridesStack,
                AppDestinations.GARAGE to garageStack,
                AppDestinations.SETTINGS to settingsStack,
            )
        }
    var current by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    // Discriminates the two kinds of NavDisplay transition: a tab switch (set here, fades)
    // vs. an in-tab sub-route push/pop (cleared by the nav lambdas below, slides). Reading
    // the route off the animation Scene isn't reliable (Nav3 stringifies the content key),
    // so we track intent explicitly. ponytail: a 1-bit flag beats parsing scene keys.
    var isTabSwitch by remember { mutableStateOf(false) }

    // Shared across the garage list/detail/add screens; Room Flow is the source of truth.
    val garageViewModel: GarageViewModel = viewModel()

    // Shared across the rides list/detail screens; Room Flow is the source of truth.
    val ridesViewModel: RidesViewModel = viewModel()

    NavigationSuiteScaffold(
        // Native three-tier: navigation bar is the dimmest surface, the screen
        // background a lighter tinted surfaceContainer, and cards (surfaceBright) the
        // brightest on top. The relationship holds in both light and dark themes.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        navigationSuiteColors =
            NavigationSuiteDefaults.colors(
                navigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        navigationSuiteItems = {
            AppDestinations.entries.forEach { dest ->
                val isSelected = dest == current
                item(
                    icon = {
                        MaterialSymbol(
                            symbolName = dest.symbolName,
                            contentDescription = stringResource(id = dest.labelRes),
                            fill = isSelected,
                        )
                    },
                    label = { Text(stringResource(id = dest.labelRes)) },
                    selected = isSelected,
                    onClick = {
                        isTabSwitch = true
                        current = dest
                    },
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // Transparent so the NavigationSuiteScaffold's surfaceContainer shows through
            // (incl. behind the status bar); the nav bar keeps its own dimmer surfaceDim.
            containerColor = Color.Transparent,
        ) { innerPadding ->
            NavDisplay(
                backStack = stacks.getValue(current),
                onBack = {
                    isTabSwitch = false
                    stacks.getValue(current).removeLastOrNull()
                },
                // Sub-route nav: new screen slides in, previous fades out at the same speed;
                // back mirrors it (top slides out, revealed screen fades in). Tab switches are
                // a quick cross-fade. predictivePop is always an in-tab back, so always slides.
                transitionSpec = {
                    if (isTabSwitch) {
                        fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS))
                    } else {
                        slideInHorizontally(tween(SLIDE_MS)) { it } togetherWith fadeOut(tween(SLIDE_MS))
                    }
                },
                popTransitionSpec = {
                    if (isTabSwitch) {
                        fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS))
                    } else {
                        fadeIn(tween(SLIDE_MS)) togetherWith slideOutHorizontally(tween(SLIDE_MS)) { it }
                    }
                },
                predictivePopTransitionSpec = { _ ->
                    fadeIn(tween(SLIDE_MS)) togetherWith slideOutHorizontally(tween(SLIDE_MS)) { it }
                },
                entryProvider =
                    entryProvider {
                        homeEntries(unitSystem)
                        ridesEntries(
                            viewModel = ridesViewModel,
                            unitSystem = unitSystem,
                            onOpen = {
                                isTabSwitch = false
                                ridesStack.add(it)
                            },
                            onBack = {
                                isTabSwitch = false
                                ridesStack.removeLastOrNull()
                            },
                        )
                        garageEntries(
                            viewModel = garageViewModel,
                            unitSystem = unitSystem,
                            onOpen = {
                                isTabSwitch = false
                                garageStack.add(it)
                            },
                            onBack = {
                                isTabSwitch = false
                                garageStack.removeLastOrNull()
                            },
                            onPopToGarage = {
                                isTabSwitch = false
                                while (garageStack.size > 1) garageStack.removeLastOrNull()
                            },
                        )
                        settingsEntries(
                            unitSystem = unitSystem,
                            onUnitSystemChange = { newSetting ->
                                UnitPrefs.set(context, newSetting)
                                unitSystem = newSetting
                            },
                            autoTrackMode = autoTrackMode,
                            onAutoTrackModeChange = { newMode ->
                                applyAutoTrackMode(context, newMode)
                                autoTrackMode = newMode
                            },
                        )
                    },
                // Outer Scaffold already insets for system bars; mark them consumed so a
                // screen's own TopAppBar/Scaffold doesn't apply the same insets again.
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
            )
        }
    }
}
