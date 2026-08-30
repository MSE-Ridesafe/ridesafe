package de.uhi.enia.ridesafe.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The calendar day an epoch-ms instant falls on — e.g. the rides list's grouping key. */
fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
