package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import kotlinx.serialization.Serializable

@Serializable data object GarageGraph

@Serializable data object GarageRoute

fun NavGraphBuilder.garageGraph() {
    navigation<GarageGraph>(startDestination = GarageRoute) {
        composable<GarageRoute> { GarageScreen() }
    }
}
