package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute : NavKey

@Serializable data object SettingsLanguageRoute : NavKey

@Serializable data object SettingsUnitsRoute : NavKey

@Serializable data object SettingsAutoTrackRoute : NavKey

fun EntryProviderScope<NavKey>.settingsEntries(
    savedAddressViewModel: SavedAddressViewModel,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
) {
    entry<SettingsRoute> {
        SettingsScreen(
            onOpenLanguage = { onOpen(SettingsLanguageRoute) },
            onOpenUnits = { onOpen(SettingsUnitsRoute) },
            onOpenAutoTrack = { onOpen(SettingsAutoTrackRoute) },
            onOpenSavedAddresses = { onOpen(SavedAddressesRoute) },
        )
    }
    savedAddressEntries(
        viewModel = savedAddressViewModel,
        onOpen = onOpen,
        onBack = onBack,
    )
    entry<SettingsLanguageRoute> {
        LanguageSettingsScreen(onBack = onBack)
    }
    entry<SettingsUnitsRoute> {
        UnitSettingsScreen(
            onBack = onBack,
        )
    }
    entry<SettingsAutoTrackRoute> {
        AutoTrackSettingsScreen(
            onBack = onBack,
        )
    }
}
