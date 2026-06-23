package de.uhi.enia.ridesafe.tracking

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reads the user's paired (bonded) Bluetooth devices for the mapping picker (GAR-08), so a
 * device can be mapped from anywhere — no need to be in the car. Requires BLUETOOTH_CONNECT;
 * the caller (vehicle detail screen) requests it first.
 */
object BluetoothDevices {
    data class Entry(
        val name: String,
        val address: String,
    )

    @SuppressLint("MissingPermission") // caller ensures BLUETOOTH_CONNECT is granted
    fun bonded(context: Context): List<Entry> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices
            .orEmpty()
            .map { Entry(name = it.name ?: it.address, address = it.address.uppercase()) }
            .sortedBy { it.name.lowercase() }
    }
}

/**
 * Detects mapped-vehicle connect/disconnect via Bluetooth ACL broadcasts and drives
 * [AutoTracking.engine]. Only an address mapped to a vehicle reaches the engine — an
 * unmapped device (headphones, a watch) is ignored. Declared as a manifest receiver, so it
 * keeps working after a reboot without the app running.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val address = device.address?.uppercase() ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val mode = AutoTrackPrefs.get(appContext)
                if (mode == AutoTrackMode.OFF) return@launch
                val vehicleId = vehicleIdFor(appContext, address) ?: return@launch // unmapped device: ignore
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> AutoTracking.engine.deviceConnected(mode, address, vehicleId)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> AutoTracking.engine.deviceDisconnected(address)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun vehicleIdFor(
        context: Context,
        address: String,
    ): Long? =
        RidesafeDatabase
            .getInstance(context)
            .vehicleDao()
            .all()
            .firstOrNull { vehicle -> vehicle.bluetoothDevices.any { it.address.equals(address, ignoreCase = true) } }
            ?.id
}
