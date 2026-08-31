@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.backup.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.BackNavIcon
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.core.components.ProgressRing
import de.uhi.enia.ridesafe.feature.backup.RideBackupImportState
import de.uhi.enia.ridesafe.feature.backup.RideBackupImportViewModel
import de.uhi.enia.ridesafe.transfer.backup.RideBackupImportCount
import de.uhi.enia.ridesafe.transfer.backup.RideBackupImportProgress

@Composable
internal fun RideBackupImportScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
    importViewModel: RideBackupImportViewModel = viewModel(),
) {
    val state by importViewModel.state.collectAsState()
    val busy = state is RideBackupImportState.Inspecting || state is RideBackupImportState.Importing
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(importViewModel::select)
        }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_import_title)) },
                navigationIcon = { BackNavIcon(onBack = onBack, showBack = showBack, enabled = !busy) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        MaterialSymbol(
                            symbolName = "settings_backup_restore",
                            contentDescription = null,
                            size = 40.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.settings_backup_import_description),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(busyLabel(state) ?: stringResource(R.string.settings_backup_import_choose))
                            }
                        }
                    }
                }
            }
        }
    }

    when (val current = state) {
        is RideBackupImportState.Inspecting -> {
            ImportProgressDialog(current.progress, stringResource(R.string.settings_backup_import_checking), importViewModel::cancel)
        }

        is RideBackupImportState.Importing -> {
            ImportProgressDialog(current.progress, stringResource(R.string.settings_backup_import_importing), importViewModel::cancel)
        }

        is RideBackupImportState.Ready -> {
            AlertDialog(
                onDismissRequest = importViewModel::dismiss,
                title = { Text(stringResource(R.string.settings_backup_import_confirm_title)) },
                text = {
                    val preview = current.candidate.preview
                    val items =
                        listOf(
                            pluralStringResource(R.plurals.settings_backup_import_preview_rides, preview.rides, preview.rides),
                            pluralStringResource(R.plurals.settings_backup_import_preview_vehicles, preview.vehicles, preview.vehicles),
                            pluralStringResource(
                                R.plurals.settings_backup_import_preview_places,
                                preview.savedAddresses,
                                preview.savedAddresses,
                            ),
                            pluralStringResource(R.plurals.settings_backup_import_preview_refuels, preview.refuels, preview.refuels),
                        ).joinToString("\n") { "• $it" }
                    Text(stringResource(R.string.settings_backup_import_confirm_message, items))
                },
                confirmButton = {
                    TextButton(
                        onClick = importViewModel::confirm,
                    ) { Text(stringResource(R.string.settings_backup_import_confirm)) }
                },
                dismissButton = { TextButton(onClick = importViewModel::dismiss) { Text(stringResource(R.string.action_cancel)) } },
            )
        }

        is RideBackupImportState.Success -> {
            AlertDialog(
                onDismissRequest = importViewModel::dismiss,
                title = { Text(stringResource(R.string.settings_backup_import_success_title)) },
                text = {
                    val result = current.result
                    val items =
                        listOf(
                            resultLine(R.plurals.settings_backup_import_result_rides, result.rides),
                            resultLine(R.plurals.settings_backup_import_result_vehicles, result.vehicles),
                            resultLine(R.plurals.settings_backup_import_result_places, result.savedAddresses),
                            resultLine(R.plurals.settings_backup_import_result_refuels, result.refuels),
                        ).joinToString("\n") { "• $it" }
                    Text(stringResource(R.string.settings_backup_import_success_message, items))
                },
                confirmButton = { TextButton(onClick = importViewModel::dismiss) { Text(stringResource(R.string.action_done)) } },
            )
        }

        is RideBackupImportState.Error -> {
            AlertDialog(
                onDismissRequest = importViewModel::dismiss,
                title = { Text(stringResource(R.string.settings_backup_import_error_title)) },
                text = { Text(stringResource(R.string.settings_backup_import_error_message, current.detail)) },
                confirmButton = { TextButton(onClick = importViewModel::dismiss) { Text(stringResource(R.string.action_done)) } },
            )
        }

        else -> {}
    }
}

/** The label the choose-a-backup button wears while a restore half is running, or null when idle. */
@Composable
private fun busyLabel(state: RideBackupImportState): String? =
    when (state) {
        is RideBackupImportState.Inspecting -> stringResource(R.string.settings_backup_import_checking)
        is RideBackupImportState.Importing -> stringResource(R.string.settings_backup_import_importing)
        else -> null
    }

/** Spins until the archive's manifest has been read, then counts rides. Not dismissable by accident. */
@Composable
private fun ImportProgressDialog(
    progress: RideBackupImportProgress,
    title: String,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(fraction = progress.fraction, size = 36.dp)
                if (progress.rides > 0) {
                    Text(stringResource(R.string.settings_backup_import_count, progress.ridesDone, progress.rides))
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** One "%d things (%d already existed)" bullet line, pluralized on the imported count. */
@Composable
private fun resultLine(
    @PluralsRes res: Int,
    count: RideBackupImportCount,
): String = pluralStringResource(res, count.imported, count.imported, count.alreadyPresent)
