package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.Ride

fun Ride.durationMillis(): Long = endedAtEpochMs?.let { (it - startedAtEpochMs).coerceAtLeast(0L) } ?: 0L
