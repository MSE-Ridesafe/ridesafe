@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface RideBackupImportState {
    data object Idle : RideBackupImportState

    data object Inspecting : RideBackupImportState

    data class Ready(
        val uri: Uri,
        val preview: RideBackupImportPreview,
    ) : RideBackupImportState

    data object Importing : RideBackupImportState

    data class Success(
        val result: RideBackupImportResult,
    ) : RideBackupImportState

    data class Error(
        val detail: String,
    ) : RideBackupImportState
}

internal class RideBackupImportViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val importer = RideBackupImporter(app)
    private val _state = MutableStateFlow<RideBackupImportState>(RideBackupImportState.Idle)
    val state: StateFlow<RideBackupImportState> = _state.asStateFlow()

    fun select(uri: Uri) {
        if (_state.value == RideBackupImportState.Importing) return
        viewModelScope.launch {
            _state.value = RideBackupImportState.Inspecting
            try {
                _state.value = RideBackupImportState.Ready(uri, importer.inspect(uri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = RideBackupImportState.Error(failure.message.orEmpty())
            }
        }
    }

    fun confirm() {
        val ready = _state.value as? RideBackupImportState.Ready ?: return
        viewModelScope.launch {
            _state.value = RideBackupImportState.Importing
            try {
                _state.value = RideBackupImportState.Success(importer.import(ready.uri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = RideBackupImportState.Error(failure.message.orEmpty())
            }
        }
    }

    fun dismiss() {
        if (_state.value != RideBackupImportState.Importing) _state.value = RideBackupImportState.Idle
    }
}

@Composable
internal fun RideBackupImportScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
    importViewModel: RideBackupImportViewModel = viewModel(),
) {
    val state by importViewModel.state.collectAsState()
    val busy = state == RideBackupImportState.Inspecting || state == RideBackupImportState.Importing
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
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack, enabled = !busy) {
                            MaterialSymbol("arrow_back", stringResource(R.string.action_back))
                        }
                    }
                },
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
                                if (busy) CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                                Text(
                                    when (state) {
                                        RideBackupImportState.Inspecting -> stringResource(R.string.settings_backup_import_checking)
                                        RideBackupImportState.Importing -> stringResource(R.string.settings_backup_import_importing)
                                        else -> stringResource(R.string.settings_backup_import_choose)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val current = state) {
        is RideBackupImportState.Ready -> {
            AlertDialog(
                onDismissRequest = importViewModel::dismiss,
                title = { Text(stringResource(R.string.settings_backup_import_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.settings_backup_import_confirm_message,
                            current.preview.rides,
                            current.preview.vehicles,
                            current.preview.savedAddresses,
                            current.preview.refuels,
                        ),
                    )
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
                    Text(
                        stringResource(
                            R.string.settings_backup_import_success_message,
                            current.result.rides.imported,
                            current.result.vehicles.imported,
                            current.result.savedAddresses.imported,
                            current.result.refuels.imported,
                            current.result.rides.alreadyPresent,
                            current.result.vehicles.alreadyPresent,
                            current.result.savedAddresses.alreadyPresent,
                            current.result.refuels.alreadyPresent,
                        ),
                    )
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
