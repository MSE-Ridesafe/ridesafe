package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.Ride
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

fun Ride.durationMillis(): Long = endedAtEpochMs?.let { (it - startedAtEpochMs).coerceAtLeast(0L) } ?: 0L
