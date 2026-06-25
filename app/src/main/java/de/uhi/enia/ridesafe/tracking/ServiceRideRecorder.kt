package de.uhi.enia.ridesafe.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The [RideRecorder] wired into [AutoTracking.recorder]: turns trip start/end from the
 * auto-tracking trigger into start/stop commands for [RideRecordingService], so recording runs
 * in a foreground service (TRK-05) instead of the trigger's short-lived broadcast process.
 *
 * Both connect and disconnect arrive inside a Bluetooth-broadcast wake, which grants the window
 * to start a foreground service from the background.
 */
class ServiceRideRecorder(
    private val context: Context,
) : RideRecorder {
    override fun onTripStart(vehicleId: Long?) {
        // GPS is required for a ride; the location FGS type is only legal with the permission held.
        // Logic-only round: grant location in system Settings to record (no request UI yet, NFR-05).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("RideRecording", "location permission not granted; not recording")
            return
        }
        val intent =
            Intent(context, RideRecordingService::class.java).apply {
                action = RideRecordingService.ACTION_START
                vehicleId?.let { putExtra(RideRecordingService.EXTRA_VEHICLE_ID, it) }
            }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun onTripEnd() {
        val intent =
            Intent(context, RideRecordingService::class.java).apply {
                action = RideRecordingService.ACTION_STOP
            }
        ContextCompat.startForegroundService(context, intent)
    }
}
