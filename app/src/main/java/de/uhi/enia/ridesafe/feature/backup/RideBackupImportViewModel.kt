package de.uhi.enia.ridesafe.feature.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uhi.enia.ridesafe.transfer.backup.RideBackupImportCandidate
import de.uhi.enia.ridesafe.transfer.backup.RideBackupImportProgress
import de.uhi.enia.ridesafe.transfer.backup.RideBackupImportResult
import de.uhi.enia.ridesafe.transfer.backup.RideBackupImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface RideBackupImportState {
    data object Idle : RideBackupImportState

    data class Inspecting(
        val progress: RideBackupImportProgress = RideBackupImportProgress(),
    ) : RideBackupImportState

    data class Ready(
        val candidate: RideBackupImportCandidate,
    ) : RideBackupImportState

    data class Importing(
        val progress: RideBackupImportProgress = RideBackupImportProgress(),
    ) : RideBackupImportState

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

    private var job: Job? = null

    fun select(uri: Uri) {
        if (_state.value is RideBackupImportState.Importing) return
        discardPending()
        job =
            viewModelScope.launch {
                _state.value = RideBackupImportState.Inspecting()
                runPhase(RideBackupImportState::Inspecting) { onProgress ->
                    RideBackupImportState.Ready(importer.inspect(uri, onProgress))
                }
            }
    }

    fun confirm() {
        val ready = _state.value as? RideBackupImportState.Ready ?: return
        job =
            viewModelScope.launch {
                _state.value = RideBackupImportState.Importing()
                runPhase(RideBackupImportState::Importing) { onProgress ->
                    RideBackupImportState.Success(importer.import(ready.candidate, onProgress))
                }
            }
    }

    /** Stops whichever half is running; a cancelled restore leaves nothing behind to clean up later. */
    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (_state.value is RideBackupImportState.Importing) return
        discardPending()
        _state.value = RideBackupImportState.Idle
    }

    /**
     * Runs one half of the restore, publishing its progress through [busyState] while it goes.
     * Late reports must not resurrect a finished dialog, so each one re-checks the current state.
     */
    private suspend fun runPhase(
        busyState: (RideBackupImportProgress) -> RideBackupImportState,
        operation: suspend ((RideBackupImportProgress) -> Unit) -> RideBackupImportState,
    ) {
        val expected = _state.value::class
        try {
            _state.value =
                operation { progress ->
                    _state.update { if (it::class == expected) busyState(progress) else it }
                }
        } catch (cancelled: CancellationException) {
            _state.value = RideBackupImportState.Idle
            throw cancelled
        } catch (failure: Exception) {
            _state.value = RideBackupImportState.Error(failure.message.orEmpty())
        }
    }

    override fun onCleared() {
        discardPending()
    }

    /** The preview's copy of the archive outlives the dialog only while the dialog is up. */
    private fun discardPending() {
        (_state.value as? RideBackupImportState.Ready)?.let { importer.discard(it.candidate) }
    }
}
