package de.uhi.enia.ridesafe.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.map.FullScreenMapHost
import de.uhi.enia.ridesafe.ui.components.map.FullScreenMapRequest
import de.uhi.enia.ridesafe.ui.components.map.LocalFullScreenMap
import de.uhi.enia.ridesafe.ui.screens.garage.GarageRoute
import de.uhi.enia.ridesafe.ui.screens.garage.GarageViewModel
import de.uhi.enia.ridesafe.ui.screens.garage.garageEntries
import de.uhi.enia.ridesafe.ui.screens.home.HomeRoute
import de.uhi.enia.ridesafe.ui.screens.home.HomeViewModel
import de.uhi.enia.ridesafe.ui.screens.home.homeEntries
import de.uhi.enia.ridesafe.ui.screens.rides.RidesRoute
import de.uhi.enia.ridesafe.ui.screens.rides.RidesViewModel
import de.uhi.enia.ridesafe.ui.screens.rides.ridesEntries
import de.uhi.enia.ridesafe.ui.screens.settings.SavedAddressViewModel
import de.uhi.enia.ridesafe.ui.screens.settings.SettingsRoute
import de.uhi.enia.ridesafe.ui.screens.settings.settingsEntries

// ponytail: animation durations are tuning knobs — bump if a transition feels off.
private const val SLIDE_MS = 250 // sub-route slide + matching fade-out of the previous screen
private const val FADE_MS = 250 // quick cross-fade between tabs

/**
 * App shell: adaptive navigation suite (bottom bar / rail / drawer) wrapping a
 * [NavDisplay]. Each tab owns a [rememberNavBackStack]; the selected tab decides which
 * stack [NavDisplay] renders, so switching tabs preserves each tab's in-tab navigation.
 * NavDisplay supplies the native default transitions and predictive-back animation.
 *
 * Preferences are read where they are used ([UnitPrefs], [AutoTrackPrefs]) rather than passed
 * down; back stacks are hoisted above the display so they persist across
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

    // NFR-05: re-read on every resume — grants can land in the system settings app, well
    // outside any launcher of ours. Keyed on the mode because that changes what is required.
    LifecycleResumeEffect(AutoTrackPrefs.get(context)) {
        PermissionState.refresh(context)
        onPauseOrDispose { }
    }

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
    // Shared snapshot signal read directly by the Rides screen. Both this counter and the screen's
    // last-handled value are saveable, so rotation cannot replay an old dismissal.
    val ridesTabReselections = rememberSaveable { mutableIntStateOf(0) }

    // Shared dashboard state sourced from vehicles and rides.
    val homeViewModel: HomeViewModel = viewModel()

    // Shared across the saved-addresses list/editor screens; Room Flow is the source of truth.
    val savedAddressViewModel: SavedAddressViewModel = viewModel()

    // The full-screen route map is hosted here, above the navigation bar, and inside the
    // activity's own (opaque) window — see FullScreenMapRequest for why it cannot be a Dialog.
    val fullScreenMap = remember { mutableStateOf<FullScreenMapRequest?>(null) }
    LaunchedEffect(current) { fullScreenMap.value = null }

    CompositionLocalProvider(LocalFullScreenMap provides fullScreenMap) {
        Box(Modifier.fillMaxSize()) {
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
                            // Missing permissions are only fixable in Settings, so that is the
                            // only tab that carries the dot.
                            badge =
                                if (dest == AppDestinations.SETTINGS && PermissionState.missing.isNotEmpty()) {
                                    {
                                        val label = stringResource(R.string.permissions_missing_badge)
                                        Badge(modifier = Modifier.semantics { contentDescription = label })
                                    }
                                } else {
                                    null
                                },
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    if (dest == AppDestinations.RIDES) ridesTabReselections.intValue++
                                } else {
                                    isTabSwitch = true
                                    current = dest
                                }
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
                                homeEntries(
                                    viewModel = homeViewModel,
                                )
                                ridesEntries(
                                    viewModel = ridesViewModel,
                                    selectionDismissRequests = ridesTabReselections,
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
                                    savedAddressViewModel = savedAddressViewModel,
                                    onOpen = {
                                        isTabSwitch = false
                                        settingsStack.add(it)
                                    },
                                    onBack = {
                                        isTabSwitch = false
                                        settingsStack.removeLastOrNull()
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
            FullScreenMapHost(fullScreenMap)
        }
    }
}
