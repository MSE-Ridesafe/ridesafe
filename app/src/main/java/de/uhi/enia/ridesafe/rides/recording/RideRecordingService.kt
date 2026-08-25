package de.uhi.enia.ridesafe.rides.recording

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.util.inAppLanguage
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
private const val TAG = "RideRecording"

class RideRecordingService : Service() {
    private lateinit var engine: RideRecordingEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        engine = RideRecordingEngine(applicationContext)
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
                // Auto-tracking started this one, so say so on the car screen; a ride the driver
                // started there needs no announcement.
                if (!intent.getBooleanExtra(EXTRA_MANUAL, false)) {
                    scope.launch { notifyRideStarted(applicationContext, vehicleId) }
                }
            }

            ACTION_STOP -> {
                val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)
                val drop = intent.getBooleanExtra(EXTRA_DROP, false)
                scope.launch {
                    // Suspends through the reconnect grace and finalizes the ride before the
                    // process can die; false means the car came back, so keep recording.
                    // stopSelfResult guards the last gap: a start delivered while we waited
                    // wins, and the recording it began keeps its foreground service.
                    if (engine.endAndAwait(immediate = manual, drop = drop)) {
                        if (!manual) notifyRideFinished(applicationContext, RecordingStatus.outcome.value)
                        if (stopSelfResult(startId)) {
                            ServiceCompat.stopForeground(this@RideRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        }
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
        // The service has no per-app locale applied to it, so its notification would otherwise
        // come out in the system language while the app is in another one.
        val strings = inAppLanguage()
        val nm = getSystemService<NotificationManager>()!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(strings.getString(R.string.recording_notification_title))
                .setContentText(strings.getString(R.string.recording_notification_text))
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
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_DROP = "drop"
        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 1

        /**
         * Start recording. Returns false when the ride could not be started, so a caller with a UI
         * — the car screen (TRK-07) — can say so instead of leaving the user tapping a dead button.
         * GPS is required for a ride and the location FGS type is only legal with the permission
         * held; the background-start rules can refuse us on top of that.
         */
        fun start(
            context: Context,
            vehicleId: Long?,
            manual: Boolean = false,
        ): Boolean {
            // Logic-only round: grant location in system Settings to record (no request UI yet, NFR-05).
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "location permission not granted; not recording")
                return false
            }
            val intent =
                Intent(context, RideRecordingService::class.java).apply {
                    action = ACTION_START
                    vehicleId?.let { putExtra(EXTRA_VEHICLE_ID, it) }
                    putExtra(EXTRA_MANUAL, manual)
                }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "not allowed to start recording from the background", e)
                false
            }
        }

        /**
         * End the ride. A [manual] stop — the driver tapping stop on the car screen — happens now
         * and reports back on screen; a Bluetooth disconnect keeps its wait-and-see window
         * (TRK-09) and announces the result in a notification instead.
         */
        fun stop(
            context: Context,
            manual: Boolean = false,
        ) {
            deliverStop(context, manual = manual, drop = false)
        }

        /** Stop the ride and throw it away instead of logging it (TRK-07): a trip as a passenger. */
        fun discard(context: Context) {
            deliverStop(context, manual = true, drop = true)
        }

        private fun deliverStop(
            context: Context,
            manual: Boolean,
            drop: Boolean,
        ) {
            val intent =
                Intent(context, RideRecordingService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_MANUAL, manual)
                    putExtra(EXTRA_DROP, drop)
                }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not deliver stop", it) }
        }
    }
}
