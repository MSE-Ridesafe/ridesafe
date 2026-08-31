package de.uhi.enia.ridesafe.analysis.event

/**
 * Speed and position at one instant. Mutable and reused by the detector rather than allocated per
 * sample; the accumulators copy out whatever they keep, so nothing outlives a call.
 */
internal class TrackState {
    var speedMps = 0.0
    var lat = 0.0
    var lon = 0.0

    // Doppler speeds of the two fixes bracketing the sample — the raw material for checking a
    // finished longitudinal event against what the car actually did (see EventAccumulator).
    var previousFixSpeedMps = 0.0
    var currentFixSpeedMps = 0.0

    // Doppler speed slope across the bracketing pair, in m/s² — the magnitude the sustained-Δv
    // path feeds the longitudinal detectors while armed.
    var slopeMps2 = 0.0
}
