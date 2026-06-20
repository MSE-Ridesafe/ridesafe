package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import kotlinx.serialization.Serializable

@Serializable data object RidesGraph
@Serializable data object RidesRoute

fun NavGraphBuilder.ridesGraph() {
    navigation<RidesGraph>(startDestination = RidesRoute) {
        composable<RidesRoute> { RidesScreen() }
    }
}
