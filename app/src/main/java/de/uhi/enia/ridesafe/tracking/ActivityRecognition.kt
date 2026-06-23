package de.uhi.enia.ridesafe.tracking

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ACTION_TRANSITION = "de.uhi.enia.ridesafe.ACTIVITY_TRANSITION"

/**
 * IN_VEHICLE start/end detection for ANY mode, via the Activity Transition API. Caller
 * guarantees ACTIVITY_RECOGNITION is granted (the setting requests it before enabling ANY).
 */
object ActivityRecognitionTracker {
    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        val transitions =
            listOf(
                transition(ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                transition(ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            )
        runCatching {
            ActivityRecognition
                .getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
        }
    }

    @SuppressLint("MissingPermission")
    fun unregister(context: Context) {
        runCatching {
            ActivityRecognition
                .getClient(context)
                .removeActivityTransitionUpdates(pendingIntent(context))
        }
    }

    private fun transition(type: Int) =
        ActivityTransition
            .Builder()
            .setActivityType(DetectedActivity.IN_VEHICLE)
            .setActivityTransition(type)
            .build()

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_TRANSITION).setPackage(context.packageName)
        // FLAG_MUTABLE: Activity Recognition fills in the transition result extras.
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

/** Receives [ActivityRecognitionTracker]'s transition broadcasts and drives the engine. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val mode = AutoTrackPrefs.get(appContext)
                val mapping = mappingMap(appContext)
                result.transitionEvents
                    .filter { it.activityType == DetectedActivity.IN_VEHICLE }
                    .forEach { event ->
                        when (event.transitionType) {
                            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                                AutoTracking.engine.activityVehicleStart(mode, mapping)
                            }

                            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                                AutoTracking.engine.activityVehicleEnd(mode)
                            }
                        }
                    }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun mappingMap(context: Context): Map<String, Long> =
        RidesafeDatabase
            .getInstance(context)
            .vehicleDao()
            .all()
            .flatMap { vehicle -> vehicle.bluetoothAddresses.map { it.uppercase() to vehicle.id } }
            .toMap()
}
