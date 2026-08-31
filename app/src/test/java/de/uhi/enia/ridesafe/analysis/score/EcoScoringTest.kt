package de.uhi.enia.ridesafe.analysis.score

import de.uhi.enia.ridesafe.data.entity.RideEco
import de.uhi.enia.ridesafe.data.file.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the efficiency profile and the eco level (ANL-03). The integration claims are exact —
 * kinetic energy per kg is Δ(v²)/2 and nothing else — and the level claims are directional: the
 * knobs are calibration, but "braking everything away must score worse than gliding" is physics,
 * and a knob change that breaks it is a bug, not a tune.
 */
class EcoScoringTest {
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

    /** A trace built from (second, speed) pairs, one fix a second unless stated. */
    private fun trace(vararg points: Pair<Double, Double>) = points.map { (t, v) -> fix(t, v) }

    /** A steady run at [mps], one fix a second. */
    private fun cruise(
        seconds: Int,
        mps: Double,
        from: Double = 0.0,
    ) = (0..seconds).map { fix(from + it, mps) }

    @Test
    fun steadyCruiseIsAllCruiseTimeAndNoBrakingEnergy() {
        val eco = rideEcoProfile(cruise(seconds = 600, mps = 25.0))!!
        assertEquals(600.0, eco.cruiseSeconds, 1e-6)
        assertEquals(0.0, eco.brakeSeconds + eco.accelSeconds + eco.idleSeconds, 1e-9)
        assertEquals(0.0, eco.brakeJPerKg, 1e-9)
        assertEquals(600.0 * 25.0, eco.meters, 1e-6)
        // A long, perfectly steady ride is the best the level can describe.
        assertEquals(3, ecoLevel(eco))
    }

    @Test
    fun standingStillIsIdleTime() {
        val eco = rideEcoProfile(cruise(seconds = 300, mps = 0.0))!!
        assertEquals(300.0, eco.idleSeconds, 1e-6)
        assertEquals(1.0, eco.idleShare, 1e-9)
        assertEquals(0.0, eco.movingSeconds, 1e-9)
    }

    /** The core physics: a braked stop is billed exactly its kinetic energy, a glide is billed nothing. */
    @Test
    fun brakingIsBilledItsKineticEnergyAndGlidingIsFree() {
        // 20 m/s shed in 5 s = 4 m/s² — hard braking. ΔKE/kg = 20²/2 = 200 J/kg.
        val braked = rideEcoProfile(cruise(seconds = 30, mps = 20.0) + trace(31.0 to 15.0, 32.0 to 10.0, 33.0 to 5.0, 34.0 to 0.0))!!
        assertEquals(200.0, braked.brakeJPerKg, 1e-6)
        assertTrue(braked.brakeSeconds > 0)

        // The same 20 m/s shed at 0.5 m/s² — a 40-second glide, below the coasting threshold.
        val glided = rideEcoProfile(cruise(seconds = 30, mps = 20.0) + (1..40).map { fix(30.0 + it, 20.0 - it * 0.5) })!!
        assertEquals(0.0, glided.brakeJPerKg, 1e-9)
        assertEquals(0.0, glided.brakeSeconds, 1e-9)
    }

    /** Speed gained hard lands in the hard-acceleration share; gained gently it does not. */
    @Test
    fun aggressiveAccelerationIsSeparatedFromGentle() {
        // 0 -> 20 m/s at 4 m/s², then steady: all acceleration energy is hard.
        val launch = trace(0.0 to 0.0, 1.0 to 4.0, 2.0 to 8.0, 3.0 to 12.0, 4.0 to 16.0, 5.0 to 20.0)
        val hard = rideEcoProfile(launch + cruise(60, 20.0, from = 6.0))!!
        assertEquals(1.0, hard.hardAccelShare, 1e-9)
        assertEquals(200.0, hard.accelJPerKg, 1e-6)

        // The same speed gained at 1 m/s²: none of it is hard.
        val gentle = rideEcoProfile((0..20).map { fix(it.toDouble(), it.toDouble()) } + cruise(60, 20.0, from = 21.0))!!
        assertEquals(0.0, gentle.hardAccelShare, 1e-9)
        assertEquals(200.0, gentle.accelJPerKg, 1e-6)
    }

    /** Stop-and-go with everything braked away must level below the same distance driven smoothly. */
    @Test
    fun stopAndGoLevelsBelowSmoothDriving() {
        // Twenty cycles: launch hard to 15 m/s, brake hard to zero, wait at the light.
        val stopAndGo =
            (0 until 20).flatMap { cycle ->
                val t0 = cycle * 60.0
                (0..5).map { fix(t0 + it, it * 3.0) } + // 0 -> 15 m/s at 3 m/s²
                    (1..5).map { fix(t0 + 5 + it, 15.0 - it * 3.0) } + // braked to 0 at 3 m/s²
                    (1..30).map { fix(t0 + 10 + it, 0.0) } // red light
            }
        val rough = rideEcoProfile(stopAndGo)!!
        val smooth = rideEcoProfile(cruise(seconds = 600, mps = 15.0))!!

        val roughIndex = ecoIndex(rough)!!
        val smoothIndex = ecoIndex(smooth)!!
        assertTrue("stop-and-go ($roughIndex) must index below smooth ($smoothIndex)", roughIndex < smoothIndex)
        assertTrue("stop-and-go must lose at least one segment", ecoLevel(rough)!! < ecoLevel(smooth)!!)
    }

    /** An outage is not driving: nothing may be integrated across a long gap. */
    @Test
    fun longGapsAreNotIntegratedAcross() {
        val eco = rideEcoProfile(listOf(fix(0.0, 25.0), fix(600.0, 25.0), fix(601.0, 25.0)))!!
        assertEquals(25.0, eco.meters, 1e-6) // only the 1-second interval
        assertEquals(1.0, eco.cruiseSeconds, 1e-9)
    }

    /** Too little to judge is no level, not a flattering one — same rule the safety score follows. */
    @Test
    fun tooLittleDrivingHasNoLevel() {
        assertNull(rideEcoProfile(emptyList()))
        assertNull(rideEcoProfile(listOf(fix(0.0, 25.0))))
        // A real but tiny profile: 30 s of driving is below the floors, so no level.
        val tiny = rideEcoProfile(cruise(seconds = 30, mps = 10.0))!!
        assertNull(ecoLevel(tiny))
    }

    /** Aggregates sum bucket-by-bucket, so a merged trip's level derives from all of its driving. */
    @Test
    fun profilesSumBucketByBucket() {
        val a = RideEco(100.0, 10.0, 20.0, 70.0, 10.0, 1000.0, 50.0, 80.0, 20.0)
        val b = RideEco(200.0, 30.0, 40.0, 140.0, 20.0, 3000.0, 70.0, 40.0, 10.0)
        val sum = a + b
        assertEquals(300.0, sum.movingSeconds, 1e-9)
        assertEquals(40.0, sum.idleSeconds, 1e-9)
        assertEquals(4000.0, sum.meters, 1e-9)
        assertEquals(120.0, sum.brakeJPerKg, 1e-9)
        assertEquals(30.0, sum.hardAccelJPerKg, 1e-9)
        // The derived rates follow the totals, not the mean of the parts' rates.
        assertEquals(120.0 / 4.0, sum.brakeJPerKgPerKm, 1e-9)
    }

    /** More braking can never raise the level — monotone, or the feedback teaches the wrong lesson. */
    @Test
    fun indexIsMonotoneInEachComponent() {
        val base = RideEco(600.0, 60.0, 100.0, 400.0, 100.0, 10_000.0, 300.0, 200.0, 40.0)
        val worseBraking = base.copy(brakeJPerKg = 900.0)
        val worseIdle = base.copy(idleSeconds = 400.0)
        val worseAccel = base.copy(hardAccelJPerKg = 180.0)
        val baseIndex = ecoIndex(base)!!
        assertTrue(ecoIndex(worseBraking)!! < baseIndex)
        assertTrue(ecoIndex(worseIdle)!! < baseIndex)
        assertTrue(ecoIndex(worseAccel)!! < baseIndex)
    }
}
