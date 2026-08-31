package de.uhi.enia.ridesafe.analysis.score

import de.uhi.enia.ridesafe.data.entity.RideEco
import de.uhi.enia.ridesafe.data.file.LocationSample

/**
 * Efficiency profiling and the eco level (ANL-03) — "favouring steady moderate speed over
 * stop-and-go, gentle acceleration, and gentle braking / gliding to a stop", scored as energy
 * accounting rather than as a fuel estimate.
 *
 * The physics that makes it car-independent: every m²/s² of kinetic energy a driver sheds through
 * the friction brakes was bought with fuel (or battery) and turned into heat, while energy shed by
 * gliding moved the car for free. Per kilogram, that waste is measurable from the speed trace alone
 * — no engine model, no vehicle data — which is why this replaced an absolute-litres estimate that
 * could never be honest across cars.
 *
 * Deliberately *not* scored: speed choice. Whether 130 was appropriate needs the speed limit, and
 * there is no affordable source for one (the reason ANL-01's speeding clause is dormant too). The
 * level judges only what the trace itself proves: braking that anticipation would have avoided,
 * time spent standing, and how the speed was gained.
 *
 * Distinct from the safety score on purpose, not by accident: safety prices *intensity* (g and
 * jerk, everything below half the harsh threshold is free), efficiency prices *energy* regardless
 * of how gently it was shed. A driver who brakes softly but late at every light is invisible to the
 * safety score and exactly who this level exists for — and cornering, a quarter of safety, costs no
 * fuel and is absent here.
 *
 * The constants below are integration constants: they decide what the stored [RideEco] aggregates
 * *are*, so changing any of them means bumping the eco stage's version — unlike [EcoKnobs], which
 * only re-reads them. This first one: below it the vehicle counts as stationary — just above zero,
 * because a filtered Doppler speed at a red light still jitters.
 */
private const val IDLE_SPEED_MPS = 0.5

/**
 * Deceleration beyond this (m/s²) is friction braking; gentler slowing is drag, engine braking and
 * gliding — the free kind. Flat-road coasting decelerates at roughly 0.3–0.6 m/s² depending on
 * speed and gear, so this sits at that band's top. Raise it and light brake-riding is credited as
 * gliding; lower it and every lift of the throttle is billed as braking. Grade is the known blind
 * spot: a downhill ride genuinely cannot avoid some braking, and without a usable slope signal it
 * is billed anyway — accepted, since GPS altitude is too noisy to correct with at this scale.
 */
private const val COAST_DECEL_MPS2 = 0.6

/** Acceleration beyond this (m/s²) counts as aggressive — a deliberate push, not traffic flow. */
private const val HARD_ACCEL_MPS2 = 2.0

/** Speed changes gentler than this (m/s²) are cruising for the regime buckets. */
private const val CRUISE_BAND_MPS2 = 0.25

/** Gaps between fixes longer than this (seconds) are outages, not driving; nothing is integrated. */
private const val MAX_FIX_GAP_SECONDS = 10.0

/**
 * Integrate a ride's efficiency profile from its filtered track, or null when there is nothing to
 * integrate. Speed is each fix's own Doppler reading at ~1 Hz — measured from carrier frequency
 * shift, so trustworthy even where the position wanders — and acceleration the difference between
 * consecutive fixes.
 */
fun rideEcoProfile(fixes: List<LocationSample>): RideEco? {
    if (fixes.size < 2) return null
    var moving = 0.0
    var idle = 0.0
    var accelS = 0.0
    var cruiseS = 0.0
    var brakeS = 0.0
    var meters = 0.0
    var brakeE = 0.0
    var accelE = 0.0
    var hardE = 0.0
    var integrated = false

    for (i in 1 until fixes.size) {
        val dt = (fixes[i].t - fixes[i - 1].t) / 1e9
        if (dt <= 0.0 || dt > MAX_FIX_GAP_SECONDS) continue
        val from = fixes[i - 1].speed.toDouble().coerceAtLeast(0.0)
        val to = fixes[i].speed.toDouble().coerceAtLeast(0.0)
        val v = (from + to) / 2.0
        val a = (to - from) / dt
        // ΔKE per kg over the interval; sign says whether it was gained or shed.
        val deltaE = (to * to - from * from) / 2.0
        integrated = true

        meters += v * dt
        if (v < IDLE_SPEED_MPS) {
            idle += dt
            continue
        }
        moving += dt
        when {
            a > CRUISE_BAND_MPS2 -> {
                accelS += dt
                accelE += deltaE
                if (a > HARD_ACCEL_MPS2) hardE += deltaE
            }

            // Real braking: deceleration beyond what coasting produces. The energy billed is the
            // whole interval's ΔKE — once the brakes are on, drag's small share isn't worth splitting.
            a < -COAST_DECEL_MPS2 -> {
                brakeS += dt
                brakeE += -deltaE
            }

            // Steady speed and gentle deceleration both land here: gliding is the behaviour the
            // level rewards, so it must not share a bucket with braking.
            else -> {
                cruiseS += dt
            }
        }
    }

    return if (integrated) RideEco(moving, idle, accelS, cruiseS, brakeS, meters, brakeE, accelE, hardE) else null
}

/**
 * Level knobs (ANL-03). Read-time only: retuning re-maps stored profiles without touching a sample
 * file, the same split the safety score uses between its stored histograms and [ScoreWeights].
 *
 * The good/bad anchors bracket each component's linear ramp — at or better than *good* the
 * component is worth its full weight, at or past *bad* it is worth nothing.
 *
 * Braking anchors, for intuition: a full friction stop from 50 km/h sheds ~96 J/kg, so in a city
 * with one forced stop per kilometre ~100 J/kg/km means braking everything away and ~40 means
 * gliding off most of it first; a motorway cruise sits near zero. The defaults are set from that
 * physics plus the shape telematics programmes report, not yet from this app's own logbook — they
 * are the number-one candidate for a calibration pass over real rides, the way
 * [ScoreWeights.referenceRate] was validated.
 *
 * ponytail: braking-per-km makes a dense-urban route score worse than a motorway run driven with
 * the same skill — route bias every telematics score shares. Normalising it away needs a road-type
 * signal the app doesn't have; revisit only if city errands pin the level at 1 in practice.
 */
data class EcoKnobs(
    val brakeGoodJPerKgKm: Double = 40.0,
    val brakeBadJPerKgKm: Double = 150.0,
    val idleGoodShare: Double = 0.10,
    val idleBadShare: Double = 0.35,
    val hardAccelGoodShare: Double = 0.10,
    val hardAccelBadShare: Double = 0.50,
    val brakeWeight: Double = 0.5,
    val idleWeight: Double = 0.25,
    val accelWeight: Double = 0.25,
    /**
     * Index cut-offs for filling segments one, two and three. A level is *segments earned*, so the
     * top one demands an index most rides won't reach — three full segments should mean the ride
     * has essentially nothing left to improve, or the display saturates and stops teaching.
     */
    val levelThresholds: List<Double> = listOf(0.35, 0.60, 0.85),
    /** Floors below which there is no level at all rather than a shaky one — same rule as scoring. */
    val minMeters: Double = 500.0,
    val minSeconds: Double = 120.0,
) {
    init {
        val sum = brakeWeight + idleWeight + accelWeight
        require(sum in 0.999..1.001) { "component weights must sum to 1, got $sum" }
    }
}

/**
 * A ride's eco level, 0..3 filled segments, or null when the profile covers too little driving to
 * judge. Monotone by construction: nothing raises it except braking less, idling less, or
 * accelerating more gently.
 */
fun ecoLevel(
    eco: RideEco?,
    knobs: EcoKnobs = EcoKnobs(),
): Int? {
    val index = ecoIndex(eco, knobs) ?: return null
    return knobs.levelThresholds.count { index >= it }
}

/**
 * The internal 0..1 quality index the level thresholds cut. Exposed for calibration and tests —
 * the histogram of this across a real logbook is what to look at before moving any knob.
 */
fun ecoIndex(
    eco: RideEco?,
    knobs: EcoKnobs = EcoKnobs(),
): Double? {
    if (eco == null) return null
    if (eco.meters < knobs.minMeters || eco.idleSeconds + eco.movingSeconds < knobs.minSeconds) return null
    return knobs.brakeWeight * ramp(eco.brakeJPerKgPerKm, knobs.brakeGoodJPerKgKm, knobs.brakeBadJPerKgKm) +
        knobs.idleWeight * ramp(eco.idleShare, knobs.idleGoodShare, knobs.idleBadShare) +
        knobs.accelWeight * ramp(eco.hardAccelShare, knobs.hardAccelGoodShare, knobs.hardAccelBadShare)
}

/** 1.0 at or better than [good], 0.0 at or past [bad], linear between. */
private fun ramp(
    value: Double,
    good: Double,
    bad: Double,
): Double = (1.0 - (value - good) / (bad - good)).coerceIn(0.0, 1.0)
