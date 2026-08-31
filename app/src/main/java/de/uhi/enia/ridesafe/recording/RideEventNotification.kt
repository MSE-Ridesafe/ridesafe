package de.uhi.enia.ridesafe.recording

import android.content.Context
import android.text.format.DateUtils
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.format.inAppLanguage
import de.uhi.enia.ridesafe.data.db.RidesafeDatabase
import de.uhi.enia.ridesafe.data.entity.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tells the driver that auto-tracking started or ended a ride (TRK-02) — on the phone, and on the
 * Android Auto screen, which is the point: without it a Bluetooth-triggered ride is invisible until
 * you next open the app.
 *
 * Only automatic starts and stops post one. A ride the driver started or stopped on the car screen
 * needs no announcement — they watched it happen, and the screen already said so.
 *
 * Two Android Auto rules shape this: a notification reaches the car screen only if it carries a
 * [CarAppExtender] and goes out through [CarNotificationManager] (the platform manager does not),
 * and importance decides how loud it is. DEFAULT badges the car's notification centre; HIGH would
 * throw a banner over the road for something that is not worth a driver's eyes (IN-1).
 */
private const val CHANNEL_ID = "ride_events"
private const val NOTIFICATION_ID = 2

/** "Recording started", naming the vehicle it was attributed to when auto-tracking knew one. */
suspend fun notifyRideStarted(
    appContext: Context,
    vehicleId: Long?,
) {
    val vehicle =
        vehicleId?.let { id ->
            withContext(Dispatchers.IO) {
                RidesafeDatabase
                    .getInstance(appContext)
                    .vehicleDao()
                    .all()
                    .firstOrNull { it.id == id }
                    ?.displayTitle()
            }
        }
    post(appContext, appContext.inAppLanguage().getString(R.string.car_notify_started), vehicle)
}

/** "Ride saved · 12:34", or why it wasn't. A ride the driver dropped reports nothing ([outcome] null). */
fun notifyRideFinished(
    appContext: Context,
    outcome: RideOutcome?,
) {
    val length = DateUtils.formatElapsedTime((outcome ?: return).lengthMs / 1_000)
    val strings = appContext.inAppLanguage()
    val title =
        when (outcome) {
            is RideOutcome.Saved -> strings.getString(R.string.car_result_saved, length)
            is RideOutcome.TooShort -> strings.getString(R.string.car_result_too_short, length)
        }
    post(appContext, title, null)
}

private fun post(
    appContext: Context,
    title: String,
    text: String?,
) {
    val manager = CarNotificationManager.from(appContext)
    manager.createNotificationChannel(
        NotificationChannelCompat
            .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(appContext.inAppLanguage().getString(R.string.car_events_channel_name))
            .build(),
    )
    val notification =
        NotificationCompat
            .Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle(title)
            .setAutoCancel(true)
            .apply { text?.let { setContentText(it) } }
            // Even with nothing overridden, the extender is what makes it show up in the car at all.
            .extend(CarAppExtender.Builder().build())
    manager.notify(NOTIFICATION_ID, notification)
}
