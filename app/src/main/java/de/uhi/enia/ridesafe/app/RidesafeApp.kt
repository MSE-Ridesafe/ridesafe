package de.uhi.enia.ridesafe.app

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
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.core.components.map.FullScreenMapHost
import de.uhi.enia.ridesafe.core.components.map.FullScreenMapRequest
import de.uhi.enia.ridesafe.core.components.map.LocalFullScreenMap
import de.uhi.enia.ridesafe.feature.garage.EditVehicleRoute
import de.uhi.enia.ridesafe.feature.garage.GarageRoute
import de.uhi.enia.ridesafe.feature.garage.GarageViewModel
import de.uhi.enia.ridesafe.feature.garage.VehicleDetailRoute
import de.uhi.enia.ridesafe.feature.garage.garageEntries
import de.uhi.enia.ridesafe.feature.home.HomeRoute
import de.uhi.enia.ridesafe.feature.home.HomeViewModel
import de.uhi.enia.ridesafe.feature.home.homeEntries
import de.uhi.enia.ridesafe.feature.logbook.EditRefuelRoute
import de.uhi.enia.ridesafe.feature.logbook.MergedRideDetailRoute
import de.uhi.enia.ridesafe.feature.logbook.RideDetailRoute
import de.uhi.enia.ridesafe.feature.logbook.RidesRoute
import de.uhi.enia.ridesafe.feature.logbook.RidesViewModel
import de.uhi.enia.ridesafe.feature.logbook.ridesEntries
import de.uhi.enia.ridesafe.feature.places.SavedAddressViewModel
import de.uhi.enia.ridesafe.feature.settings.SettingsMenuRoutes
import de.uhi.enia.ridesafe.feature.settings.SettingsRoute
import de.uhi.enia.ridesafe.feature.settings.settingsEntries
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.recording.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.widget.RecordingStatusBar

// ponytail: animation durations are tuning knobs — bump if a transition feels off.
private const val SLIDE_MS = 250 // sub-route slide + matching fade-out of the previous screen
private const val FADE_MS = 250 // quick cross-fade between tabs

/**
 * Opens [key] from a tab's list pane: whatever detail run is showing gets replaced, not stacked
 * under it. On two panes the list stays tappable beside an open detail, so pushing on every tap
 * would grow the stack without bound — each stale detail alive in memory, and every one of them
 * an extra back press once the window drops to a single pane. Tapping the already-open route is
 * a no-op, which keeps that detail's state. The stack never grows past list + one open screen
 * (+ a form pushed from *inside* the detail pane, which callers add directly).
 */
private fun openFromList(
    stack: MutableList<NavKey>,
    key: NavKey,
) {
    if (stack.lastOrNull() == key) return
    while (stack.size > 1) stack.removeLastOrNull()
    stack.add(key)
}

/**
 * Pops [key] — a screen asking to close itself. A no-op unless [key] is still what is showing:
 * back events can arrive faster than recomposition swaps the screen (a double-tapped back arrow,
 * or a tap landing during the pop animation), and an unguarded second pop would take the screen
 * *underneath* with it — or empty the stack entirely, which NavDisplay rejects with
 * "NavDisplay backstack cannot be empty" and brings the whole app down.
 */
private fun popOwn(
    stack: MutableList<NavKey>,
    key: NavKey,
) {
    if (stack.size > 1 && stack.lastOrNull() == key) stack.removeLastOrNull()
}

/**
 * App shell: adaptive navigation suite (bottom bar / rail / drawer) wrapping a
 * [NavDisplay]. Each tab owns a [rememberNavBackStack]; the selected tab decides which
 * stack [NavDisplay] renders, so switching tabs preserves each tab's in-tab navigation.
 * NavDisplay supplies the native default transitions and predictive-back animation.
 *
 * Preferences are read where they are used ([AutoTrackPrefs]) rather than passed
 * down; back stacks are hoisted above the display so they persist across
 * every route. The garage flow's [GarageViewModel] is hoisted here too (one app-scoped
 * instance shared by its three screens), since Nav3 has no graph scope.
 *
 * Adding a screen: declare a @Serializable NavKey + an entry in that tab's *Navigation.kt
 * and push it onto the tab's back stack. Adding a tab: new root route + entry builder + an
 * AppDestinations entry + a back stack below.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RidesafeApp() {
    val context = LocalContext.current

    // NFR-05: re-read on every resume — grants can land in the system settings app, well
    // outside any launcher of ours. Keyed on the mode because that changes what is required.
    LifecycleResumeEffect(AutoTrackPrefs.get(context)) {
        PermissionState.refresh(context)
        onPauseOrDispose { }
    }

    // One directive, read twice: it decides where the window splits AND whether a detail pane still
    // needs its own back arrow, so the two can never disagree. Material's default splits from 840dp
    // up — tablet landscape, but not tablet portrait.
    //
    // showBack = !twoPane only reaches the screens a list can open directly: pinned beside the list
    // they are already "here", so an arrow back to nothing is noise. The forms sit a level deeper
    // and keep their own cancel regardless — that X discards edits, it is not navigation.
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    val twoPane = directive.maxHorizontalPartitions > 1
    // PopLatest, not the default PopUntilScaffoldValueChange: with a placeholder filling the empty
    // detail pane, the scaffold value never changes, so the default finds nothing to pop and lets
    // back fall through to finishing the activity. One entry per press also matches the phone.
    val listDetail =
        rememberListDetailSceneStrategy<NavKey>(
            directive = directive,
            backNavigationBehavior = BackNavigationBehavior.PopLatest,
        )

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

    // What each tab's list pane marks as open: the deepest route the list knows how to mark —
    // searched from the top so "Saved addresses" stays lit while its editor sits a level deeper.
    val openRide =
        when (val key = ridesStack.lastOrNull { it is RideDetailRoute || it is MergedRideDetailRoute || it is EditRefuelRoute }) {
            // Each maps to the matching TimelineEntry.stableKey.
            is RideDetailRoute -> "r${key.id}"

            is MergedRideDetailRoute -> "g${key.groupId}"

            is EditRefuelRoute -> "f${key.id}"

            else -> null
        }
    val openVehicle =
        when (val key = garageStack.lastOrNull { it is VehicleDetailRoute || it is EditVehicleRoute }) {
            is VehicleDetailRoute -> key.id
            is EditVehicleRoute -> key.id
            else -> null
        }
    val openSetting = settingsStack.lastOrNull { it in SettingsMenuRoutes }

    // Discriminates the two kinds of NavDisplay transition: a tab switch (set here, fades)
    // vs. an in-tab sub-route push/pop (cleared by the nav lambdas below, slides). Reading
    // the route off the animation Scene isn't reliable (Nav3 stringifies the content key),
    // so we track intent explicitly. ponytail: a 1-bit flag beats parsing scene keys.
    var isTabSwitch by remember { mutableStateOf(false) }

    // Shared across the garage list/detail/add screens; Room Flow is the source of truth.
    val garageViewModel: GarageViewModel = viewModel()

    // Only so the floating recording bar can name the car it is logging against.
    val vehicles by garageViewModel.vehicles.collectAsState()

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
                    // The recording bar floats over whatever tab is showing, inside the Scaffold's
                    // content area so it clears the navigation bar. Nothing lays out around it: it
                    // is only ever on screen while a ride records, and it collapses to nothing after.
                    Box(
                        Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .fillMaxSize(),
                    ) {
                        NavDisplay(
                            backStack = stacks.getValue(current),
                            // Groups each tab's list route with everything below it into one
                            // two-pane scene; routes without pane metadata (Home) fall through to
                            // the single-pane scene unchanged.
                            sceneStrategies = listOf(listDetail),
                            onBack = {
                                isTabSwitch = false
                                // Never pops the tab root: two system backs can land in the same
                                // frame, before recomposition deregisters the callback, and the
                                // second would empty the stack ("backstack cannot be empty").
                                val stack = stacks.getValue(current)
                                if (stack.size > 1) stack.removeLastOrNull()
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
                                        selectedKey = openRide,
                                        showBack = !twoPane,
                                        selectionDismissRequests = ridesTabReselections,
                                        onOpen = { key ->
                                            isTabSwitch = false
                                            // A refuel opened while a ride detail is showing came
                                            // from inside that detail (its attached-refuels list):
                                            // it stacks so back returns to the ride. Everything
                                            // else this tab opens is list-level and replaces.
                                            val top = ridesStack.lastOrNull()
                                            val overDetail =
                                                key is EditRefuelRoute &&
                                                    (top is RideDetailRoute || top is MergedRideDetailRoute)
                                            if (overDetail) ridesStack.add(key) else openFromList(ridesStack, key)
                                        },
                                        onBack = { key ->
                                            isTabSwitch = false
                                            popOwn(ridesStack, key)
                                        },
                                    )
                                    garageEntries(
                                        viewModel = garageViewModel,
                                        selectedId = openVehicle,
                                        showBack = !twoPane,
                                        onOpen = { key ->
                                            isTabSwitch = false
                                            // The edit form opens from inside the detail pane and
                                            // stacks on top of its vehicle; the rest is list-level.
                                            if (key is EditVehicleRoute) {
                                                garageStack.add(key)
                                            } else {
                                                openFromList(garageStack, key)
                                            }
                                        },
                                        onBack = { key ->
                                            isTabSwitch = false
                                            popOwn(garageStack, key)
                                        },
                                        onPopToGarage = {
                                            isTabSwitch = false
                                            while (garageStack.size > 1) garageStack.removeLastOrNull()
                                        },
                                    )
                                    settingsEntries(
                                        savedAddressViewModel = savedAddressViewModel,
                                        selected = openSetting,
                                        showBack = !twoPane,
                                        onOpen = { key ->
                                            isTabSwitch = false
                                            // The address editor opens from inside the detail pane
                                            // and stacks on top of its list; menu taps are list-level.
                                            if (key in SettingsMenuRoutes) {
                                                openFromList(settingsStack, key)
                                            } else {
                                                settingsStack.add(key)
                                            }
                                        },
                                        onBack = { key ->
                                            isTabSwitch = false
                                            popOwn(settingsStack, key)
                                        },
                                    )
                                },
                            // The Box above already applied (and consumed) the Scaffold's insets, so a
                            // screen's own TopAppBar/Scaffold doesn't apply the same insets again.
                            modifier = Modifier.fillMaxSize(),
                        )
                        RecordingStatusBar(
                            vehicles = vehicles,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp),
                        )
                    }
                }
            }
            FullScreenMapHost(fullScreenMap)
        }
    }
}
