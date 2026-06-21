package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object RidesRoute : NavKey

fun EntryProviderScope<NavKey>.ridesEntries() {
    entry<RidesRoute> { RidesScreen() }
}
