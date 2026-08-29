package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.DetailPlaceholder
import de.uhi.enia.ridesafe.ui.components.ListPaneFocusSink
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute : NavKey

@Serializable data object SettingsLanguageRoute : NavKey

@Serializable data object SettingsUnitsRoute : NavKey

@Serializable data object SettingsCurrencyRoute : NavKey

@Serializable data object SettingsAutoTrackRoute : NavKey

@Serializable data object SettingsReconnectGraceRoute : NavKey

@Serializable data object SettingsMinRideLengthRoute : NavKey

@Serializable data object SettingsBackupImportRoute : NavKey

/** Ties this tab's list and detail routes into one scene, distinct from the other tabs'. */
internal const val SETTINGS_SCENE = "settings"

/** The routes the settings menu can mark as open — everything exactly one tap from the menu. */
internal val SettingsMenuRoutes: Set<NavKey> =
    setOf(
        SettingsLanguageRoute,
        SettingsUnitsRoute,
        SavedAddressesRoute,
        SettingsAutoTrackRoute,
        SettingsReconnectGraceRoute,
        SettingsMinRideLengthRoute,
    )

/**
 * Settings tab entries: the menu plus every sub-screen below it, including the saved-addresses
 * flow. The pane metadata groups them into one list-detail scene, so on a wide window the menu
 * stays put on the left while its sub-screen fills the right. [selected] is the menu row to mark as
 * open — one of [SettingsMenuRoutes], which stays lit even when the saved-address editor sits a
 * further level down. [showBack] is false once both panes are visible, where a back arrow on a
 * pinned pane would be meaningless.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.settingsEntries(
    savedAddressViewModel: SavedAddressViewModel,
    selected: NavKey?,
    showBack: Boolean,
    onOpen: (NavKey) -> Unit,
    onBack: (NavKey) -> Unit,
) {
    entry<SettingsRoute>(
        metadata =
            ListDetailSceneStrategy.listPane(sceneKey = SETTINGS_SCENE) {
                DetailPlaceholder(stringResource(R.string.placeholder_select_setting))
            },
    ) {
        ListPaneFocusSink {
            SettingsScreen(
                onOpenLanguage = { onOpen(SettingsLanguageRoute) },
                onOpenUnits = { onOpen(SettingsUnitsRoute) },
                onOpenCurrency = { onOpen(SettingsCurrencyRoute) },
                onOpenAutoTrack = { onOpen(SettingsAutoTrackRoute) },
                onOpenReconnectGrace = { onOpen(SettingsReconnectGraceRoute) },
                onOpenMinRideLength = { onOpen(SettingsMinRideLengthRoute) },
                onOpenSavedAddresses = { onOpen(SavedAddressesRoute) },
                onOpenBackupImport = { onOpen(SettingsBackupImportRoute) },
                selected = selected,
            )
        }
    }
    savedAddressEntries(
        viewModel = savedAddressViewModel,
        showBack = showBack,
        onOpen = onOpen,
        onBack = onBack,
    )
    // TODO: Add "showBack" and metadata for SettingsCurrencyRoute and SettingsBackupImportRoute
    entry<SettingsLanguageRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) {
        LanguageSettingsScreen(onBack = { onBack(SettingsLanguageRoute) }, showBack = showBack)
    }
    entry<SettingsUnitsRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) {
        UnitSettingsScreen(
            onBack = { onBack(SettingsUnitsRoute) },
            showBack = showBack,
        )
    }
    entry<SettingsCurrencyRoute> {
        CurrencySettingsScreen(onBack = onBack)
    }

    entry<SettingsAutoTrackRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) {
        AutoTrackSettingsScreen(
            onBack = { onBack(SettingsAutoTrackRoute) },
            showBack = showBack,
        )
    }
    entry<SettingsReconnectGraceRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) {
        ReconnectGraceSettingsScreen(
            onBack = { onBack(SettingsReconnectGraceRoute) },
            showBack = showBack,
        )
    }
    entry<SettingsMinRideLengthRoute>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = SETTINGS_SCENE)) {
        MinRideLengthSettingsScreen(
            onBack = { onBack(SettingsMinRideLengthRoute) },
            showBack = showBack,
        )
    }
    entry<SettingsBackupImportRoute> {
        RideBackupImportScreen(onBack = onBack)
    }
}
