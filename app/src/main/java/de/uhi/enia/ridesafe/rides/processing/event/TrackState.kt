package de.uhi.enia.ridesafe.rides.processing.event

/**
 * Speed and position at one instant. Mutable and reused by the detector rather than allocated per
 * sample; the accumulators copy out whatever they keep, so nothing outlives a call.
 */
internal class TrackState {
    var speedMps = 0.0
    var lat = 0.0
    var lon = 0.0
}
