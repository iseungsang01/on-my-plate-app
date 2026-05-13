package com.lss.onmyplate.nativeplanner.domain.parser

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AppointmentTitleFormatter {
    const val DefaultDurationMillis = 60 * 60 * 1_000L

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("M/d HHmm")
    private val timeFormatter = DateTimeFormatter.ofPattern("HHmm")

    fun defaultEnd(startAt: Long, endAt: Long?): Long = endAt ?: startAt + DefaultDurationMillis

    fun format(startAt: Long?, endAt: Long?, location: String?, zoneId: ZoneId): String {
        if (startAt == null) return ""
        val start = Instant.ofEpochMilli(startAt).atZone(zoneId)
        val end = Instant.ofEpochMilli(defaultEnd(startAt, endAt)).atZone(zoneId)
        val place = location?.trim()?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "${dateTimeFormatter.format(start)}-${timeFormatter.format(end)}$place"
    }
}
