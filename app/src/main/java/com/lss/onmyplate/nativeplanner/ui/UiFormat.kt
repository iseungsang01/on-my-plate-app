package com.lss.onmyplate.nativeplanner.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val zoneId = ZoneId.of("Asia/Seoul")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatDateTime(millis: Long?): String =
    millis?.let { dateTimeFormatter.format(Instant.ofEpochMilli(it).atZone(zoneId)) }.orEmpty()

fun formatDay(millis: Long): String = dayFormatter.format(Instant.ofEpochMilli(millis).atZone(zoneId))
fun formatTime(millis: Long): String = timeFormatter.format(Instant.ofEpochMilli(millis).atZone(zoneId))

fun parseDateTimeOrNull(value: String): Long? =
    runCatching { LocalDateTime.parse(value.trim(), dateTimeFormatter).atZone(zoneId).toInstant().toEpochMilli() }.getOrNull()


fun millisToLocalDateTime(millis: Long?): LocalDateTime? =
    millis?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDateTime() }

fun localDateTimeToMillis(value: LocalDateTime): Long = value.atZone(zoneId).toInstant().toEpochMilli()

fun combineDateAndTime(date: LocalDate, time: LocalTime): Long = localDateTimeToMillis(LocalDateTime.of(date, time))
