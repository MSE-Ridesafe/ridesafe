package de.uhi.enia.ridesafe.rides.processing.score

import de.uhi.enia.ridesafe.data.DYNAMICS_BINS
import de.uhi.enia.ridesafe.data.DYNAMICS_G_PER_BIN
import de.uhi.enia.ridesafe.data.DYNAMICS_JERK_PER_BIN
import de.uhi.enia.ridesafe.data.DirectionHistogram
import de.uhi.enia.ridesafe.data.RideDynamics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers safety scoring (ANL-01). Two tests here are load-bearing and the rest are guard rails.
 *
 * [scoreIsARateNotACount] is the first: the whole design rests on penalty being divided by exposure,
 * and the failure it catches — a long ride scoring worse than a short one for identical driving — is
 * invisible in any single ride's number and only shows up as users asking why their commute beats
 * their road trip.
 *
 * [aggregatingRidesIsNotAveragingScores] is the second: because the 0–100 curve is nonlinear, a
 * month's score has to be built by summing penalties and mapping once. Averaging the ride scores
 * produces a plausible-looking number that is simply wrong, and the test pins the gap between the
 * two so nobody "simplifies" the aggregate into a mean.
 */
class SafetyScoringTest {
    /**
     * Seconds spent at one force / onset-rate combination, everything else quiet. Sparse on purpose:
     * quiet time sits in bin 0 and contributes nothing, so leaving it out changes no penalty — the
     * ride's length is carried by [dynamics]'s own exposure figure, which is the only place it counts.
     */
    private fun histogram(
        seconds: Double,
        magnitudeG: Double,
        jerkGPerS: Double,
    ): DirectionHistogram {
        val magnitude = MutableList(DYNAMICS_BINS) { 0f }
        val jerk = MutableList(DYNAMICS_BINS) { 0f }
        magnitude[bin(magnitudeG, DYNAMICS_G_PER_BIN)] = seconds.toFloat()
        jerk[bin(jerkGPerS, DYNAMICS_JERK_PER_BIN)] = seconds.toFloat()
        return DirectionHistogram(magnitude, jerk)
    }

    private fun bin(
        value: Double,
        width: Double,
    ) = (value / width).toInt().coerceIn(0, DYNAMICS_BINS - 1)

    private fun dynamics(
        qualifiedSeconds: Double,
        braking: DirectionHistogram = DirectionHistogram.EMPTY,
        acceleration: DirectionHistogram = DirectionHistogram.EMPTY,
        cornering: DirectionHistogram = DirectionHistogram.EMPTY,
        coverage: Double = 1.0,
    ) = RideDynamics(
        braking = braking,
        acceleration = acceleration,
        cornering = cornering,
        qualifiedSeconds = qualifiedSeconds,
        qualifiedMeters = qualifiedSeconds * 15.0,
        totalSeconds = qualifiedSeconds / coverage,
    )

    /** A firm brake: well past the jerk threshold and most of the way to the force bypass. */
    private fun brake(seconds: Double) = histogram(seconds, magnitudeG = 0.45, jerkGPerS = 1.4)

    /**
     * Identical driving over twice the distance must score the same. The penalty doubles and so does
     * the exposure it is divided by, so the rate — and therefore the score — cannot move.
     *
     * Credibility is switched off here because it deliberately breaks this invariance: a longer ride
     * is judged more on its own evidence and less on the assumed average, which is the whole point of
     * it and is pinned separately by [shortRidesAreShrunkTowardTheAverage].
     */
    @Test
    fun scoreIsARateNotACount() {
        val weights = ScoreWeights(priorSeconds = 0.0)
        val short = scoreRide(dynamics(600.0, braking = brake(6.0)), weights = weights)
        val long = scoreRide(dynamics(1200.0, braking = brake(12.0)), weights = weights)

        assertNotNull(short)
        assertEquals(short!!.total, long!!.total)
        // Guards the guard: if both landed on 100 the equality above would hold for the wrong reason.
        assertTrue("expected a middling score, got ${short.total}", short.total in 1..95)
    }

    /**
     * An hour of driving scores the same whether it was recorded as one ride or six, and that answer
     * is *not* the average of the six ride scores. The gap is large because each short ride is pulled
     * toward the assumed average while the hour as a whole is not.
     */
    @Test
    fun aggregatingRidesIsNotAveragingScores() {
        val whole = scoreRide(dynamics(3600.0, braking = brake(36.0)))!!
        val parts = List(6) { scoreRide(dynamics(600.0, braking = brake(6.0)))!! }

        assertEquals(whole.total, aggregateScore(parts)!!.total)
        val naiveMean = parts.map { it.total }.average()
        assertTrue(
            "averaging ride scores should differ from the real aggregate, got $naiveMean vs ${whole.total}",
            naiveMean - whole.total > 5,
        )
    }

    /**
     * Driving that never approaches an event accrues no penalty at all, so the only thing keeping the
     * score off 100 is the assumed average — which is why a clean half-hour lands in the mid-90s
     * rather than exactly at the top. That gap is intentional: a perfect score is earned over many
     * rides, not one.
     */
    @Test
    fun smoothDrivingScoresNearTheTop() {
        val score = scoreRide(dynamics(1800.0))!!
        assertEquals(0.0, score.brakingPenalty, 0.0)
        assertTrue("expected a near-perfect score, got ${score.total}", score.total >= 94)
    }

    /** Nothing a driver does can raise the score except driving more gently. */
    @Test
    fun rougherDrivingNeverScoresHigher() {
        val scores =
            listOf(0.0, 1.0, 3.0, 8.0, 20.0).map { seconds ->
                scoreRide(dynamics(1800.0, braking = brake(seconds)))!!.total
            }
        assertEquals(scores.sortedDescending(), scores)
        assertTrue("severity should actually move the score, got $scores", scores.first() - scores.last() > 20)
    }

    /**
     * One hard brake two minutes into a drive is weak evidence about a driver; the same rate sustained
     * for an hour is strong evidence. Without credibility the two would score identically, and every
     * short trip would swing to an extreme on a single maneuver.
     */
    @Test
    fun shortRidesAreShrunkTowardTheAverage() {
        val brief = scoreRide(dynamics(150.0, braking = brake(1.5)))!!
        val sustained = scoreRide(dynamics(3600.0, braking = brake(36.0)))!!

        assertTrue(
            "the brief ride should be judged far less harshly, got ${brief.total} vs ${sustained.total}",
            brief.total - sustained.total > 20,
        )
    }

    /**
     * The failure this design exists to prevent: a ride the sensors could not measure produces an
     * empty histogram, which is arithmetically indistinguishable from flawless driving. Coverage is
     * the only thing that separates them, so a ride mostly spent below the speed gate — or recorded
     * without a rotation vector — must come back with no score rather than a perfect one.
     */
    @Test
    fun unmeasurableRidesHaveNoScoreRatherThanAPerfectOne() {
        assertNull("too little qualifying driving", scoreRide(dynamics(60.0)))
        assertNull("mostly unmeasurable", scoreRide(dynamics(300.0, coverage = 0.1)))
        assertNull("nothing measured at all", scoreRide(dynamics(0.0, coverage = 1.0)))
    }

    /**
     * Cornering is scored on how fast lateral force built, never on how much of it there was. Lateral
     * force is v²/r, so a tight turn at walking pace reaches levels that mean nothing about how it was
     * driven — the same reason the detector gives cornering no force bypass. The identical dwell time
     * on braking, where force does mean something, must land hard.
     */
    @Test
    fun corneringIsJudgedOnRateOfForceNotForce() {
        val clean = scoreRide(dynamics(1800.0))!!.total
        val hardCorners = scoreRide(dynamics(1800.0, cornering = histogram(60.0, 0.9, 0.0)))!!.total
        val hardBrakes = scoreRide(dynamics(1800.0, braking = histogram(60.0, 0.9, 0.0)))!!.total

        assertEquals("sustained lateral force alone is not harsh", clean, hardCorners)
        assertTrue("sustained braking force is harsh, got $hardBrakes", hardBrakes < clean - 20)
    }

    /** A window with nothing scoreable in it has no score — not zero, and not a default. */
    @Test
    fun anEmptyWindowHasNoScore() {
        assertNull(aggregateScore(emptyList()))
    }
}
