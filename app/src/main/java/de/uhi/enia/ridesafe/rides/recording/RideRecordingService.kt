package de.uhi.enia.ridesafe.rides.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import de.uhi.enia.ridesafe.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts ride recording in the foreground (TRK-05) so capture survives the app being backgrounded
 * and the launching Bluetooth-receiver process going cold. Driven by [ServiceRideRecorder]: a
 * mapped-device connect starts it, a disconnect stops it. One service lifecycle == one ride —
 * including across a short dropout: a disconnect only ends the ride once the reconnect grace
 * expires without the car coming back (TRK-09), so the service stays up in between.
 */
class RideRecordingService : Service() {
    private lateinit var engine: RideRecordingEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // The grace is read once per ride: changing it mid-ride applies to the next one.
        engine = RideRecordingEngine(applicationContext, reconnectGraceMs = ReconnectGracePrefs.get(this).millis)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_START -> {
                val vehicleId =
                    if (intent.hasExtra(EXTRA_VEHICLE_ID)) intent.getLongExtra(EXTRA_VEHICLE_ID, -1L) else null
                engine.onTripStart(vehicleId)
            }

            ACTION_STOP -> {
                scope.launch {
                    // Suspends through the reconnect grace and finalizes the ride before the
                    // process can die; false means the car came back, so keep recording.
                    // stopSelfResult guards the last gap: a start delivered while we waited
                    // wins, and the recording it began keeps its foreground service.
                    if (engine.endAndAwait() && stopSelfResult(startId)) {
                        ServiceCompat.stopForeground(this@RideRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    }
                }
            }

            else -> {
                stopSelf()
            }
        }
        // Killed mid-trip => the open ride is finalized by recovery on next app start (NFR-06),
        // so there's nothing to resume from a null re-delivery.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService<NotificationManager>()!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(getString(R.string.recording_notification_text))
                .setSmallIcon(R.drawable.ic_recording)
                .setOngoing(true)
                .build()
        // location type is safe: ServiceRideRecorder only starts us with the permission held.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    companion object {
        const val ACTION_START = "de.uhi.enia.ridesafe.action.START_RECORDING"
        const val ACTION_STOP = "de.uhi.enia.ridesafe.action.STOP_RECORDING"
        const val EXTRA_VEHICLE_ID = "vehicleId"
        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 1
    }
}
