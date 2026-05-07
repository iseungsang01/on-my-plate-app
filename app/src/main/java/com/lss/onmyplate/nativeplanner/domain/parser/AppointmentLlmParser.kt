package com.lss.onmyplate.nativeplanner.domain.parser

import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult

fun interface AppointmentLlmParser {
    suspend fun parse(rawText: String, receivedAt: Long): AppointmentParseResult?
}
