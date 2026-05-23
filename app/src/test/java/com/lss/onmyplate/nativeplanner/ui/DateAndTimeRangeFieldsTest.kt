package com.lss.onmyplate.nativeplanner.ui

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateAndTimeRangeFieldsTest {
    @Test
    fun parsesTimeInputAsHourMinute() {
        assertEquals(LocalTime.of(9, 0), parseTimeTextOrNull("09:00"))
        assertEquals(LocalTime.of(18, 30), parseTimeTextOrNull("18:30"))
        assertEquals(LocalTime.of(9, 5), parseTimeTextOrNull("9:05"))
        assertEquals(LocalTime.of(12, 15), parseTimeTextOrNull("1215"))
    }

    @Test
    fun doesNotParsePartialCompactInput() {
        assertNull(parseTimeTextOrNull("121"))
    }

    @Test
    fun rejectsOutOfRangeNumericTimeInput() {
        assertNull(parseTimeTextOrNull(""))
        assertNull(parseTimeTextOrNull("24"))
        assertNull(parseTimeTextOrNull("2460"))
        assertNull(parseTimeTextOrNull("1260"))
    }
}
