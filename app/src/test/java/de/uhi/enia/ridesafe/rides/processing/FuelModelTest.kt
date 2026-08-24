package de.uhi.enia.ridesafe.rides.processing

import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the VT-Micro fuel model (ANL-03). The load-bearing test is [coefficientTableIsPhysical]:
 * the model is thirty-two numbers transcribed from a paper, and a single wrong digit or sign yields
 * a number that still looks like fuel. Pinning it to what a car actually does is the only check that
 * fails when the table is wrong.
 */
class FuelModelTest {
    private fun fix(
        seconds: Double,
        mps: Double,
    ) = LocationSample(
        t = (seconds * 1e9).toLong(),
        lat = 50.0,
        lon = 8.0,
        alt = 0.0,
        speed = mps.toFloat(),
        bearing = 90f,
        accuracy = 5f,
    )

    /** A steady run at [mps], one fix a second. */
    private fun cruise(
        seconds: Int,
        mps: Double,
    ) = (0..seconds).map { fix(it.toDouble(), mps) }

    private fun lPer100Km(
        mps: Double,
        mps2: Double = 0.0,
    ) = vtMicroLitersPerSecond(mps, mps2) / mps * 100_000.0

    /**
     * The four facts that make the coefficient table recognisably a car. Ranges rather than exact
     * values, because what is being tested is that the transcription describes a light-duty vehicle
     * at all — not the study's decimals, which the constants themselves are.
     */
    @Test
    fun coefficientTableIsPhysical() {
        // Idling burns something, and it is about a litre or two an hour.
        val idleLitersPerHour = vtMicroLitersPerSecond(0.0, 0.0) * 3600
        assertTrue("idle at $idleLitersPerHour L/h is not a car", idleLitersPerHour in 1.0..3.0)

        // Cruising at 100 km/h lands in single-digit L/100 km.
        val motorway = lPer100Km(27.78)
        assertTrue("100 km/h at $motorway L/100 km is not a car", motorway in 5.0..12.0)

        // Pulling away costs more than holding the same speed, and coasting down costs less. This is
        // what the two coefficient matrices exist for; swapping them flips both comparisons.
        val steady = vtMicroLitersPerSecond(10.0, 0.0)
        assertTrue("accelerating must cost more than cruising", vtMicroLitersPerSecond(10.0, 1.0) > steady)
        assertTrue("decelerating must cost less than cruising", vtMicroLitersPerSecond(10.0, -1.0) < steady)

        // The matrices share their a = 0 column, so the model must not jump across the sign change.
        assertEquals(vtMicroLitersPerSecond(10.0, 1e-9), vtMicroLitersPerSecond(10.0, -1e-9), 1e-9)
    }

    /** Above the fitted range the cubic speed term runs away; the clamp is what keeps it a car. */
    @Test
    fun absurdSpeedIsClampedToTheFittedRange() {
        val insane = lPer100Km(83.0) // 300 km/h, faster than the model was ever shown
        assertTrue("unclamped, the model diverges: $insane L/100 km", insane < 30.0)
        // Clamping means identical rates above the ceiling, which is the under-estimate we accept.
        assertEquals(vtMicroLitersPerSecond(45.0, 0.0), vtMicroLitersPerSecond(60.0, 0.0), 1e-12)
    }

    /** Integration over a steady track is the rate times the time, and all of it cruising. */
    @Test
    fun steadyCruiseIntegratesToRateTimesDuration() {
        val fuel = estimateRideFuel(cruise(seconds = 100, mps = 25.0))!!
        assertEquals(vtMicroLitersPerSecond(25.0, 0.0) * 100, fuel.totalLiters, 1e-9)
        assertEquals(fuel.totalLiters, fuel.cruiseLiters, 1e-9)
        assertEquals(0.0, fuel.idleLiters, 1e-12)
    }

    /** Standing still is the stop-and-go signal the breakdown exists to show. */
    @Test
    fun stationaryFuelLandsInTheIdleBucket() {
        val fuel = estimateRideFuel(cruise(seconds = 60, mps = 0.0))!!
        assertEquals(fuel.totalLiters, fuel.idleLiters, 1e-9)
        assertEquals(1.0, fuel.idleShare, 1e-9)
    }

    /** A pull-away and a slow-down have to be told apart, or the breakdown says nothing. */
    @Test
    fun accelerationAndDecelerationAreSeparated() {
        val fixes = listOf(fix(0.0, 0.0), fix(1.0, 4.0), fix(2.0, 8.0), fix(3.0, 4.0), fix(4.0, 0.0))
        val fuel = estimateRideFuel(fixes)!!
        assertTrue("a pull-away must register", fuel.accelLiters > 0)
        assertTrue("a slow-down must register", fuel.decelLiters > 0)
        assertTrue("accelerating burns more than the same speeds decelerating", fuel.accelLiters > fuel.decelLiters)
        assertEquals(0.0, fuel.cruiseLiters, 1e-12)
    }

    /**
     * A tunnel leaves a hole in the track. Integrating across it would invent fuel for minutes of
     * driving nobody measured, so the interval contributes nothing.
     */
    @Test
    fun longGapsAreNotIntegratedAcross() {
        val bridged = estimateRideFuel(listOf(fix(0.0, 25.0), fix(600.0, 25.0), fix(601.0, 25.0)))!!
        assertEquals(vtMicroLitersPerSecond(25.0, 0.0), bridged.totalLiters, 1e-9) // the 1 s only
    }

    /** Too little track to difference is no estimate, not a zero one. */
    @Test
    fun aTrackWithNothingToIntegrateHasNoEstimate() {
        assertNull(estimateRideFuel(emptyList()))
        assertNull(estimateRideFuel(listOf(fix(0.0, 25.0))))
        assertNull(estimateRideFuel(listOf(fix(0.0, 25.0), fix(0.0, 25.0)))) // duplicate timestamp
    }

    private fun vehicle(
        fuelType: FuelType = FuelType.PETROL,
        economy: Double? = null,
    ) = Vehicle(
        id = 1,
        make = "Test",
        model = "Car",
        licensePlate = "X",
        fuelType = fuelType,
        mileageKm = 0,
        fuelEconomy = economy,
    )

    /** Calibration moves the magnitude onto the user's car and leaves the ride's shape alone. */
    @Test
    fun ratedEconomyScalesTheEstimateWithoutChangingItsShape() {
        val raw = estimateRideFuel(cruise(seconds = 100, mps = 25.0))!!
        val calibrated = raw.forVehicle(vehicle(economy = vtMicroReferenceLPer100Km / 2))!!

        assertEquals(raw.totalLiters / 2, calibrated.totalLiters, 1e-9)
        assertEquals(raw.idleShare, calibrated.idleShare, 1e-9)
        // A car whose rated economy is exactly the model's own reference is the model, unscaled.
        assertEquals(
            raw.totalLiters,
            raw.forVehicle(vehicle(economy = vtMicroReferenceLPer100Km))!!.totalLiters,
            1e-9,
        )
        // The reference itself has to be a plausible mixed-cycle figure, or every car is mis-scaled.
        assertTrue("reference $vtMicroReferenceLPer100Km L/100 km", vtMicroReferenceLPer100Km in 5.0..12.0)
    }

    /** No economy on file is the raw model, not a guess at one. */
    @Test
    fun withoutRatedEconomyTheRawModelIsShown() {
        val raw = estimateRideFuel(cruise(seconds = 100, mps = 25.0))!!
        assertEquals(raw.totalLiters, raw.forVehicle(vehicle())!!.totalLiters, 1e-12)
    }

    /** Litres are meaningless for a drivetrain the model doesn't describe. */
    @Test
    fun onlyPetrolAndDieselGetAnEstimate() {
        val raw = estimateRideFuel(cruise(seconds = 100, mps = 25.0))!!
        assertNotNull(raw.forVehicle(vehicle(FuelType.PETROL)))
        assertNotNull(raw.forVehicle(vehicle(FuelType.DIESEL)))
        assertNull(raw.forVehicle(vehicle(FuelType.ELECTRIC)))
        assertNull(raw.forVehicle(vehicle(FuelType.HYBRID)))
        assertNull(raw.forVehicle(vehicle(FuelType.LPG)))
        assertNull("a ride with no vehicle has nothing to attribute fuel to", raw.forVehicle(null))
    }
}
