package de.uhi.enia.ridesafe.feature.places

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uhi.enia.ridesafe.data.db.RidesafeDatabase
import de.uhi.enia.ridesafe.data.entity.SavedAddress
import de.uhi.enia.ridesafe.domain.place.rematchRides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Saved addresses state, app-scoped (hoisted in RidesafeApp) so the list and editor screens share
 * one instance. The Room [Flow] is the single source of truth. Every mutation re-matches the rides
 * (ADR-07, stored-match model), so an added/edited/removed place immediately re-labels past rides.
 */
class SavedAddressViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = RidesafeDatabase.getInstance(app)
    private val dao = db.savedAddressDao()
    private val rideDao = db.rideDao()

    // Prefetched at app launch (Eagerly) so the first visit to the Saved Addresses screen reads an
    // already-loaded list instead of paying the cold Room query mid-transition.
    val addresses: StateFlow<List<SavedAddress>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun address(id: Long): Flow<SavedAddress?> = dao.observe(id)

    fun add(address: SavedAddress) {
        viewModelScope.launch {
            dao.insert(address)
            rematchRides(rideDao, dao)
        }
    }

    fun update(address: SavedAddress) {
        viewModelScope.launch {
            dao.update(address)
            rematchRides(rideDao, dao)
        }
    }

    fun delete(address: SavedAddress) {
        viewModelScope.launch {
            dao.delete(address)
            rematchRides(rideDao, dao)
        }
    }
}
