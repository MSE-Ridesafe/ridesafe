package de.uhi.enia.ridesafe.analysis.score

import de.uhi.enia.ridesafe.data.entity.Ride
import de.uhi.enia.ridesafe.data.entity.RideEco
import de.uhi.enia.ridesafe.data.entity.SafetyScore

/**
 * ANL-01/ANL-03 coupling: a ride is rated by both the safety score and the eco level, or by
 * neither. The two are computed from different sensors with different floors, so half-rated rides
 * genuinely occur — a clean GPS track recorded without a usable rotation vector has an eco level
 * but no safety score, and a stop-and-go crawl can qualify for safety below eco's distance floor.
 * Showing one judgment while calling the same driving unmeasurable for the other reads as a bug,
 * so every score consumer — the detail screens and the window aggregates alike — goes through
 * [ratedScore]/[ratedEco] instead of the raw columns.
 *
 * Read-time on purpose, like the levels and windows themselves: nothing stored changes, and the
 * raw eco profile stays readable everywhere — the time/energy composition is honest at any length,
 * only the rating isn't.
 */
fun Ride.isRated(): Boolean = score != null && ecoLevel(eco) != null

/** The ride's safety score toward display and window aggregates — null unless [isRated]. */
val Ride.ratedScore: SafetyScore? get() = if (isRated()) score else null

/** The ride's eco profile toward pooled levels — null unless [isRated]. */
val Ride.ratedEco: RideEco? get() = if (isRated()) eco else null
