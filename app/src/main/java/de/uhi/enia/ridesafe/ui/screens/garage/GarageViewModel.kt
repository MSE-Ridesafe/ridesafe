package de.uhi.enia.ridesafe.ui.screens.garage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uhi.enia.ridesafe.data.BtDevice
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Garage state, app-scoped (hoisted in RidesafeApp) so the list/detail/add screens share
 * one instance. The Room [Flow]s are the single source of truth, so an insert from the add
 * screen propagates to the list automatically.
 */
class GarageViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val dao = RidesafeDatabase.getInstance(app).vehicleDao()

    val vehicles: Flow<List<Vehicle>> = dao.observeAll()

    fun vehicle(id: Long): Flow<Vehicle?> = dao.observe(id)

    fun addVehicle(
        vehicle: Vehicle,
        makePrimary: Boolean,
    ) {
        viewModelScope.launch { dao.addVehicle(vehicle, makePrimary) }
    }

    fun updateVehicle(
        vehicle: Vehicle,
        makePrimary: Boolean,
    ) {
        viewModelScope.launch { dao.updateVehicle(vehicle, makePrimary) }
    }

    fun deleteVehicle(vehicle: Vehicle) {
        viewModelScope.launch { dao.deleteVehicle(vehicle) }
    }

    /** Map/unmap a Bluetooth device to a vehicle for auto-tracking (GAR-08). */
    fun linkBluetooth(
        vehicle: Vehicle,
        device: BtDevice,
    ) {
        viewModelScope.launch { dao.update(vehicle.copy(bluetoothDevices = vehicle.bluetoothDevices + device)) }
    }

    fun unlinkBluetooth(
        vehicle: Vehicle,
        address: String,
    ) {
        viewModelScope.launch {
            dao.update(vehicle.copy(bluetoothDevices = vehicle.bluetoothDevices.filterNot { it.address == address }))
        }
    }
}
