@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.garage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.EmptyState
import de.uhi.enia.ridesafe.core.components.LicensePlateChip
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.data.entity.Vehicle
import de.uhi.enia.ridesafe.data.file.loadVehicleImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GarageScreen(
    modifier: Modifier = Modifier,
    vehicles: List<Vehicle>,
    onVehicleClick: (Long) -> Unit,
    // The vehicle whose detail pane is showing. Null on a phone, where the detail covers the list.
    onAddVehicle: () -> Unit,
    selectedId: Long? = null,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.screen_garage_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddVehicle) {
                MaterialSymbol(
                    symbolName = "add",
                    contentDescription = stringResource(R.string.garage_add_vehicle),
                )
            }
        },
    ) { innerPadding ->
        if (vehicles.isEmpty()) {
            EmptyState(
                symbolName = "directions_car",
                title = stringResource(R.string.garage_empty_title),
                message = stringResource(R.string.garage_empty_message),
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vehicles, key = { it.id }) { vehicle ->
                    VehicleCard(
                        vehicle = vehicle,
                        isOpen = vehicle.id == selectedId,
                        onClick = { onVehicleClick(vehicle.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isOpen) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceBright
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VehicleImage(vehicle = vehicle, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${vehicle.make} ${vehicle.model}".trim(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LicensePlateChip(plate = vehicle.licensePlate)
            }
            if (vehicle.isPrimary) {
                MaterialSymbol(
                    symbolName = "favorite",
                    contentDescription = stringResource(R.string.garage_primary_marker),
                    fill = true,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Vehicle photo, with the car symbol retained as the empty-state fallback. */
@Composable
internal fun VehicleImage(
    modifier: Modifier = Modifier,
    vehicle: Vehicle? = null,
    size: Dp,
    color: Color? = null,
) {
    val context = LocalContext.current
    val bitmap by
        produceState<android.graphics.Bitmap?>(
            initialValue = null,
            key1 = vehicle?.vehicleUuid,
            key2 = vehicle?.updatedAtEpochMs,
        ) {
            value = withContext(Dispatchers.IO) { vehicle?.let { loadVehicleImage(context, it) } }
        }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(MaterialTheme.shapes.large)
                .background(color ?: MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = stringResource(R.string.garage_vehicle_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MaterialSymbol(
                symbolName = "directions_car",
                contentDescription = null,
                color = MaterialTheme.colorScheme.onSurface,
                size = size / 2,
            )
        }
    }
}
