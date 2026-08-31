package de.uhi.enia.ridesafe.feature.home

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.feature.home.ui.HomeScreen
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey

/** Home tab entries. Add child screens by declaring a @Serializable NavKey and an entry<Route> here. */
fun EntryProviderScope<NavKey>.homeEntries(viewModel: HomeViewModel) {
    entry<HomeRoute> {
        val state by viewModel.dashboard.collectAsState()
        HomeScreen(
            state = state,
            onSelectVehicle = viewModel::selectVehicle,
        )
    }
}
