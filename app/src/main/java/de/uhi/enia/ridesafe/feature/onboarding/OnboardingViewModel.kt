package de.uhi.enia.ridesafe.feature.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uhi.enia.ridesafe.data.db.RidesafeDatabase
import de.uhi.enia.ridesafe.data.entity.BtDevice
import de.uhi.enia.ridesafe.data.entity.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Owns the wizard's database writes, so a mid-save recomposition or step change cannot cancel
 * them — a rememberCoroutineScope launch dies with its composition; viewModelScope does not.
 * Step position and the created car's id stay in the composable as rememberSaveable: they are
 * navigation state and must survive process death, which a ViewModel alone would not.
 */
internal class OnboardingViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val vehicleDao = RidesafeDatabase.getInstance(app).vehicleDao()

    // Latch against a double-tapped save inserting the car twice while the first insert runs.
    private var savingCar = false

    fun observeVehicle(id: Long): Flow<Vehicle?> = vehicleDao.observe(id)

    /** Insert or update the wizard's car; [onSaved] fires with its id once the row exists. */
    fun saveCar(
        vehicle: Vehicle,
        makePrimary: Boolean,
        onSaved: (Long) -> Unit,
    ) {
        if (savingCar) return
        savingCar = true
        viewModelScope.launch {
            val id =
                if (vehicle.id == 0L) {
                    vehicleDao.addVehicle(vehicle, makePrimary)
                } else {
                    // A back-visit edits the created car (GAR-03 path).
                    vehicleDao.updateVehicle(vehicle, makePrimary)
                    vehicle.id
                }
            onSaved(id)
            savingCar = false
        }
    }

    fun linkDevice(
        vehicle: Vehicle,
        device: BtDevice,
    ) {
        viewModelScope.launch {
            vehicleDao.update(vehicle.copy(bluetoothDevices = vehicle.bluetoothDevices + device))
        }
    }

    fun unlinkDevice(
        vehicle: Vehicle,
        address: String,
    ) {
        viewModelScope.launch {
            vehicleDao.update(vehicle.copy(bluetoothDevices = vehicle.bluetoothDevices.filterNot { it.address == address }))
        }
    }
}
