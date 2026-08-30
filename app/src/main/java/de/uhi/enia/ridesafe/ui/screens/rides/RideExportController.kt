package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.export.CompletedRideExport
import de.uhi.enia.ridesafe.export.RideExportFormat
import de.uhi.enia.ridesafe.export.RideExportRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RideExportState {
    data object Idle : RideExportState

    data object Exporting : RideExportState

    data class Success(
        val export: CompletedRideExport,
    ) : RideExportState

    data object Error : RideExportState
}

/** Small lifecycle-aware operation guard/state holder, driven by the Rides ViewModel scope. */
class RideExportController(
    private val scope: CoroutineScope,
    private val operation: suspend (List<RideExportRequest>, RideExportFormat) -> CompletedRideExport,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val _state = MutableStateFlow<RideExportState>(RideExportState.Idle)
    val state: StateFlow<RideExportState> = _state.asStateFlow()

    fun start(
        requests: List<RideExportRequest>,
        format: RideExportFormat,
    ): Boolean {
        if (requests.isEmpty() || !_state.compareAndSet(RideExportState.Idle, RideExportState.Exporting)) return false
        scope.launch {
            try {
                _state.value = RideExportState.Success(operation(requests, format))
            } catch (cancelled: CancellationException) {
                _state.value = RideExportState.Idle
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
                _state.value = RideExportState.Error
            } finally {
                if (_state.value == RideExportState.Exporting) _state.value = RideExportState.Idle
            }
        }
        return true
    }

    fun consumeResult() {
        if (_state.value is RideExportState.Success || _state.value == RideExportState.Error) {
            _state.value = RideExportState.Idle
        }
    }
}

/** Snapshot selected logical entries in display order, deduplicating only within each entry. */
fun exportRequests(
    entries: List<LogbookEntry>,
    selectedKeys: Set<String>,
): List<RideExportRequest> {
    return entries.mapNotNull { entry ->
        if (entry.key !in selectedKeys) return@mapNotNull null
        val ids = entry.rideIds.distinct()
        ids.takeIf { it.isNotEmpty() }?.let { RideExportRequest(entry.key, it) }
    }
}
