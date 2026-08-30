package de.uhi.enia.ridesafe.ui.components.map

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/**
 * Whether the device currently has a network at all, kept live as the default network comes and
 * goes. Deliberately just "any network" rather than a validated-internet or wifi-specific check:
 * the only job is to stop a map from spinning forever when there is plainly nothing to load tiles
 * over, and cellular loads them as well as wifi does.
 */
@Composable
internal fun rememberIsOnline(): Boolean {
    val manager = LocalContext.current.getSystemService(ConnectivityManager::class.java)
    var online by remember { mutableStateOf(manager.activeNetwork != null) }
    DisposableEffect(manager) {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online = true
                }

                override fun onLost(network: Network) {
                    // Re-check rather than assume offline: switching wifi → cellular delivers the
                    // old network's onLost after the new one's onAvailable.
                    online = manager.activeNetwork != null
                }
            }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return online
}

/**
 * What a map's loading cover shows while the map hasn't reported in: the spinner while the load
 * can still make progress, or — with no network to fetch tiles over — the reason the wait would
 * otherwise never end. Swaps back to the spinner by itself when the connection returns, and the
 * map (which retries tiles on its own) then loads and lifts the cover as usual.
 */
@Composable
internal fun MapLoadingIndicator() {
    if (rememberIsOnline()) {
        CircularProgressIndicator()
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MaterialSymbol(
                symbolName = "wifi_off",
                contentDescription = null,
                size = 40.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.map_offline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Opaque cover centered on [MapLoadingIndicator], hiding a map surface that has not loaded yet. */
@Composable
fun BoxScope.MapLoadingCover(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceBright),
        contentAlignment = Alignment.Center,
    ) { MapLoadingIndicator() }
}
