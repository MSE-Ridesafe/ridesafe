package de.uhi.enia.ridesafe.ui.screens.garage

import androidx.annotation.StringRes
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.Vehicle

/** Localized label for a [FuelType] (the enum is stored language-neutrally). */
@StringRes
fun FuelType.labelRes(): Int =
    when (this) {
        FuelType.PETROL -> R.string.fuel_type_petrol
        FuelType.DIESEL -> R.string.fuel_type_diesel
        FuelType.ELECTRIC -> R.string.fuel_type_electric
        FuelType.HYBRID -> R.string.fuel_type_hybrid
        FuelType.LPG -> R.string.fuel_type_lpg
        FuelType.OTHER -> R.string.fuel_type_other
    }

/**
 * Identity label: make + model, with the optional nickname appended in quotes —
 * e.g. `Volkswagen Golf "Daily"`, or just `Volkswagen Golf` when unnamed. Always shown
 * alongside the license plate wherever a vehicle is identified.
 */
fun Vehicle.displayTitle(): String = if (name.isBlank()) "$make $model" else "$make $model \"${name.trim()}\""

/** Sample data for @Preview composables only. */
internal val previewVehicles =
    listOf(
        Vehicle(
            id = 1,
            name = "Daily Golf",
            make = "Volkswagen",
            model = "Golf",
            licensePlate = "HH AB 123",
            fuelType = FuelType.PETROL,
            mileageKm = 82_000,
            isPrimary = true,
            year = 2018,
            fuelEconomy = 6.4,
            tankSize = 50.0,
        ),
        Vehicle(
            id = 2,
            name = "", // no nickname — exercises the make/model-only path
            make = "Renault",
            model = "Zoe",
            licensePlate = "HH ZE 42",
            fuelType = FuelType.ELECTRIC,
            mileageKm = 23_000,
            isPrimary = false,
            year = 2021,
        ),
    )
