package de.uhi.enia.ridesafe.feature.home

import de.uhi.enia.ridesafe.data.entity.Refuel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal fun addRefuelCosts(
    activityByDay: Map<LocalDate, ActivityBar>,
    refuels: List<Refuel>,
    zone: ZoneId,
): Map<LocalDate, ActivityBar> {
    val result = activityByDay.toMutableMap()
    refuels.forEach { refuel ->
        val day = Instant.ofEpochMilli(refuel.timestampEpochMs).atZone(zone).toLocalDate()
        val existing = result[day] ?: ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
        // Bucketed per currency, never summed across codes — the chart picks the selected
        // currency's bucket. Uppercased because imported backups may carry lowercase codes
        // (RideBackupImport matches them case-insensitively).
        val code = refuel.currencyCode.uppercase(Locale.ROOT)
        val costs = existing.costMinorByCurrency.toMutableMap()
        costs[code] = (costs[code] ?: 0L) + refuel.totalPriceMinor
        result[day] = existing.copy(costMinorByCurrency = costs)
    }
    return result
}
