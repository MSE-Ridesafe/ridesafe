package de.uhi.enia.ridesafe.feature.logbook.ui

import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.domain.ride.TripType

/** The symbol for a trip kind: the list's own route glyph for a single ride, the merge one for a trip. */
internal fun TripType.icon(): String =
    when (this) {
        TripType.SINGLE -> "route"
        TripType.MERGED -> "merge"
    }

internal fun TripType.labelRes(): Int =
    when (this) {
        TripType.SINGLE -> R.string.rides_filter_trip_single
        TripType.MERGED -> R.string.rides_filter_trip_merged
    }
