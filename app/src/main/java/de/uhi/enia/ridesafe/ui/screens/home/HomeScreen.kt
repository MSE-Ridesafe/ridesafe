package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    unitSystem: UnitSystemSetting = UnitSystemSetting.AUTOMATIC,
) {
    val context = LocalContext.current
    val formattedDistance = formatDistance(context, 5000.0, unitSystem)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.screen_home_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Greeting(name = "Android")
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.sample_distance_label, formattedDistance),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun Greeting(
    name: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(id = R.string.greeting_hello, name),
        modifier = modifier,
    )
}
