package de.uhi.enia.ridesafe.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The calendar day an epoch-ms instant falls on — e.g. the rides list's grouping key. */
fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

fun LocalDate.startOfDayMs(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Long.toUtcDay(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
