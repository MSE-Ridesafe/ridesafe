package de.uhi.enia.ridesafe.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.rides.recording.RecordingStatus
import de.uhi.enia.ridesafe.rides.recording.RideRecordingService
import de.uhi.enia.ridesafe.rides.recording.RunningRide
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ridesafe on the Android Auto screen (TRK-07): start a ride, stop it, throw it away, and see how
 * long it has been running. Nothing else — a driver reads a car screen at a glance, and everything
 * worth more than a glance (the route, the score, the logbook) stays on the phone.
 *
 * Distribution note: a ride logger is not one of Android Auto's app categories, so this is not
 * publishable on Play as a car app. It reaches a real head unit through an internal test track, and
 * the Desktop Head Unit during development.
 */
class RidesafeCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator
                .Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = RidesafeSession()
}

private class RidesafeSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = RideControlScreen(carContext)
}

/** Characters the duration span replaces; what shows when no ride is running. */
private const val ELAPSED_PLACEHOLDER = "—"

/** A result older than this is history, not news: don't report it to someone opening the app later. */
private const val OUTCOME_MAX_AGE_MS = 2 * 60 * 1000L

/**
 * The car app's root: what is being recorded, and the two controls that change it.
 *
 * Only one of the controls is ever live — [Action.Builder.setEnabled] greys out start while a ride
 * runs and stop while none does, so the screen never offers something the engine would silently
 * refuse. Discard sits apart in the header strip, in the error colour, because it is the one action
 * here that destroys data.
 *
 * Colour is carried by the text, never by a button fill: the host already styles its buttons, and a
 * screen full of coloured slabs is noise a driver has to read past. See [CarPalette] for where the
 * accents come from.
 *
 * The shape obeys the host's template quota: a task may show five templates, and a push only counts
 * as a free refresh while the template title, the number of rows and every row title stay the same.
 * Row text is free, so the ticking clock lives there and the row title stays put — which also lets
 * the enabled states flip along on the same refresh.
 */
private class RideControlScreen(
    carContext: CarContext,
) : Screen(carContext) {
    private val ui = CarUi(carContext)

    /** Name of the vehicle the running ride is on, resolved off the running ride's id. */
    private var vehicleLabel: String? = null

    init {
        // Tick only while the screen is on the car display, and only while something is running.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingStatus.running.collectLatest { running ->
                    vehicleLabel = running?.vehicleId?.let { vehicleName(it) }
                    while (true) {
                        invalidate()
                        // Idle: paint once and wait for the next start/stop instead of polling.
                        if (running == null) break
                        delay(1_000.milliseconds) // ponytail: 1 Hz is the coarsest tick a seconds display can have
                    }
                }
            }
        }
        // Report a finished ride only while this screen is the one on top, so the result never
        // lands on top of the vehicle picker or a confirmation.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                RecordingStatus.outcome.collect { outcome ->
                    if (outcome == null || SystemClock.elapsedRealtime() - outcome.atElapsedMs > OUTCOME_MAX_AGE_MS) {
                        return@collect
                    }
                    RecordingStatus.consumeOutcome()
                    screenManager.push(RideOutcomeScreen(carContext, outcome))
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val running = RecordingStatus.running.value
        return PaneTemplate
            .Builder(
                Pane
                    .Builder()
                    .addRow(elapsedRow(running))
                    .addRow(vehicleRow(running))
                    .addAction(
                        action(R.string.car_start, "play_arrow", CarAccent.AFFIRMATIVE, enabled = running == null) { onStart() },
                    ).addAction(
                        // Stopping ends the ride, so it reads in the same register as discarding.
                        action(R.string.car_stop, "stop", CarAccent.DESTRUCTIVE, enabled = running != null) {
                            RideRecordingService.stop(carContext, manual = true)
                        },
                    ).build(),
            ).setHeader(
                Header
                    .Builder()
                    .setTitle(ui.string(R.string.app_name))
                    .setStartHeaderAction(Action.APP_ICON)
                    .build(),
            ).setActionStrip(
                // Destructive, so it keeps its distance from the two buttons the driver reaches for
                // while moving — and it still asks first (UX-01).
                ActionStrip
                    .Builder()
                    .addAction(
                        // An action strip validates its titles as plain text, so the error colour
                        // rides on the icon here rather than on the word.
                        action(
                            R.string.car_discard,
                            "delete",
                            CarAccent.DESTRUCTIVE,
                            enabled = running != null,
                            emphasiseTitle = false,
                        ) { onDiscard() },
                    ).build(),
            ).build()
    }

    /**
     * The clock, in the row's prominent slot.
     *
     * A row title takes only a [DurationSpan] or a DistanceSpan — never plain text of ours that
     * changes, because the pane counts as refreshed only while every row title stays put. The
     * comparison happens "not counting spans" though, so the characters stay [ELAPSED_PLACEHOLDER]
     * forever while the span over them renders the running duration in the host's own words.
     * Underneath sits the exact time, which is free to change because row text always is.
     */
    private fun elapsedRow(running: RunningRide?): Row {
        val seconds = running?.let { (SystemClock.elapsedRealtimeNanos() - it.startedElapsedNanos) / 1_000_000_000L }
        val title =
            SpannableString(ELAPSED_PLACEHOLDER).apply {
                seconds?.let { setSpan(DurationSpan.create(it), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
        return Row
            .Builder()
            .setImage(ui.icon("timer", CarAccent.NEUTRAL), Row.IMAGE_TYPE_ICON)
            .setTitle(title)
            .addText(seconds?.let { DateUtils.formatElapsedTime(it) } ?: ui.string(R.string.car_not_recording))
            .build()
    }

    /** Which car this is being logged against — a label that holds still, and a name that does not. */
    private fun vehicleRow(running: RunningRide?): Row =
        Row
            .Builder()
            .setImage(ui.icon("directions_car", CarAccent.NEUTRAL), Row.IMAGE_TYPE_ICON)
            .setTitle(ui.string(R.string.car_vehicle))
            .addText(
                when {
                    running == null -> ui.string(R.string.car_no_ride_vehicle)
                    else -> vehicleLabel ?: ui.string(R.string.car_no_vehicle)
                },
            ).build()

    /** Which vehicle is this ride on? Ask whenever the garage leaves a choice to make (TRK-08). */
    private fun onStart() {
        lifecycleScope.launch {
            val vehicles = withContext(Dispatchers.IO) { vehicleDao().all() }.sortedWith(GARAGE_ORDER)
            if (vehicles.size <= 1) {
                startRide(vehicles.firstOrNull()?.id)
            } else {
                screenManager.push(VehiclePickerScreen(carContext, vehicles) { startRide(it) })
            }
        }
    }

    private fun startRide(vehicleId: Long?) {
        if (!RideRecordingService.start(carContext, vehicleId, manual = true)) {
            CarToast.makeText(carContext, R.string.car_start_failed, CarToast.LENGTH_LONG).show()
        }
    }

    private fun onDiscard() {
        screenManager.push(
            ConfirmScreen(carContext, R.string.car_discard_confirm, R.string.car_discard) {
                RideRecordingService.discard(carContext)
                screenManager.pop()
                CarToast.makeText(carContext, R.string.car_discarded, CarToast.LENGTH_LONG).show()
            },
        )
    }

    private suspend fun vehicleName(id: Long): String? =
        withContext(Dispatchers.IO) {
            // The garage is a handful of rows; a full read beats carrying a query for one lookup.
            vehicleDao().all().firstOrNull { it.id == id }?.displayTitle()
        }

    private fun vehicleDao() = RidesafeDatabase.getInstance(carContext.applicationContext).vehicleDao()

    private fun action(
        titleRes: Int,
        symbolName: String,
        accent: CarAccent,
        enabled: Boolean,
        emphasiseTitle: Boolean = true,
        onClick: () -> Unit,
    ): Action =
        Action
            .Builder()
            .setTitle(ui.string(titleRes).let { if (emphasiseTitle) ui.emphasise(it, accent) else it })
            .setIcon(ui.icon(symbolName, accent))
            .setEnabled(enabled)
            .setOnClickListener(onClick)
            .build()
}

/** Primary vehicle first, then how the Garage sorts (make, then model). */
private val GARAGE_ORDER =
    compareByDescending<Vehicle> { it.isPrimary }
        .thenBy { it.make.lowercase() }
        .thenBy { it.model.lowercase() }
