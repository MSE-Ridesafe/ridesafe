package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey

/** Home tab entries. Add child screens by declaring a @Serializable NavKey and an entry<Route> here. */
fun EntryProviderScope<NavKey>.homeEntries(viewModel: HomeViewModel) {
    entry<HomeRoute> {
        val state by viewModel.dashboard.collectAsState(initial = HomeDashboardState())
        HomeScreen(
            state = state,
        )
    }
}
