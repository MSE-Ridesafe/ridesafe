package de.uhi.enia.ridesafe.car

import android.text.format.DateUtils
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.entity.Vehicle
import de.uhi.enia.ridesafe.data.entity.displayTitle
import de.uhi.enia.ridesafe.data.file.deleteRide
import de.uhi.enia.ridesafe.recording.RideOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which vehicle is this ride on (TRK-08)? Pushed on a manual start when the garage holds more than
 * one, so a ride is never quietly attributed to the wrong car — the logbook is the kind of record
 * people hand to a tax office.
 *
 * The list is the vehicles the caller already read, primary first; no loading state, no query here.
 */
internal class VehiclePickerScreen(
    carContext: CarContext,
    private val vehicles: List<Vehicle>,
    private val onPicked: (Long) -> Unit,
) : Screen(carContext) {
    private val ui = CarUi(carContext)

    override fun onGetTemplate(): Template {
        // The host caps how many rows it will draw while driving. A garage past that cap is
        // truncated rather than scrolled; those rides can still be started from the phone.
        val limit =
            carContext
                .getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val list = ItemList.Builder()
        vehicles.take(limit).forEach { vehicle ->
            list.addItem(
                Row
                    .Builder()
                    .setTitle(vehicle.displayTitle())
                    .apply { if (vehicle.licensePlate.isNotBlank()) addText(vehicle.licensePlate) }
                    .setOnClickListener {
                        screenManager.pop()
                        onPicked(vehicle.id)
                    }.build(),
            )
        }
        return ListTemplate
            .Builder()
            .setSingleList(list.build())
            .setHeader(
                Header
                    .Builder()
                    .setTitle(ui.string(R.string.car_pick_vehicle))
                    .setStartHeaderAction(Action.BACK)
                    .build(),
            ).build()
    }
}

/**
 * What became of the ride that just ended: logged, or dropped for being shorter than the minimum
 * (TRK-10). Without this the driver only finds out by opening the phone.
 *
 * A saved ride can still be deleted from here — the passenger trip you only notice afterwards —
 * behind the confirmation UX-01 asks for.
 */
internal class RideOutcomeScreen(
    carContext: CarContext,
    private val outcome: RideOutcome,
) : Screen(carContext) {
    private val ui = CarUi(carContext)

    override fun onGetTemplate(): Template {
        val length = DateUtils.formatElapsedTime(outcome.lengthMs / 1_000)
        val message =
            when (outcome) {
                is RideOutcome.Saved -> ui.string(R.string.car_result_saved, length)
                is RideOutcome.TooShort -> ui.string(R.string.car_result_too_short, length)
            }
        return MessageTemplate
            .Builder(message)
            .apply { if (outcome is RideOutcome.Saved) addAction(deleteAction(outcome.rideId)) }
            .addAction(
                Action
                    .Builder()
                    .setTitle(ui.string(R.string.car_done))
                    .setOnClickListener { screenManager.pop() }
                    .build(),
            ).setHeader(
                Header
                    .Builder()
                    .setTitle(ui.string(R.string.car_ride_finished))
                    .setStartHeaderAction(Action.BACK)
                    .build(),
            ).build()
    }

    private fun deleteAction(rideId: Long): Action =
        Action
            .Builder()
            .setTitle(ui.emphasise(ui.string(R.string.car_delete), CarAccent.DESTRUCTIVE))
            .setOnClickListener {
                screenManager.push(
                    ConfirmScreen(carContext, R.string.car_delete_confirm, R.string.car_delete) {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { deleteRide(carContext.applicationContext, rideId) }
                            CarToast.makeText(carContext, R.string.car_deleted, CarToast.LENGTH_LONG).show()
                            screenManager.popToRoot()
                        }
                    },
                )
            }.build()
}

/**
 * "Are you sure?" for the two ways a ride can be thrown away. Deleting a ride is one of the actions
 * UX-01 wants confirmed, and a car screen is exactly where a mis-tap happens.
 */
internal class ConfirmScreen(
    carContext: CarContext,
    private val messageRes: Int,
    private val confirmRes: Int,
    private val onConfirm: () -> Unit,
) : Screen(carContext) {
    private val ui = CarUi(carContext)

    override fun onGetTemplate(): Template =
        MessageTemplate
            .Builder(ui.string(messageRes))
            .addAction(
                Action
                    .Builder()
                    // The half that destroys something is the half that gets the error colour;
                    // "keep" stays plain so the two never read as equally weighted.
                    .setTitle(ui.emphasise(ui.string(confirmRes), CarAccent.DESTRUCTIVE))
                    .setOnClickListener(onConfirm)
                    .build(),
            ).addAction(
                Action
                    .Builder()
                    .setTitle(ui.string(R.string.car_keep))
                    .setOnClickListener { screenManager.pop() }
                    .build(),
            ).setHeader(Header.Builder().setStartHeaderAction(Action.BACK).build())
            .build()
}
