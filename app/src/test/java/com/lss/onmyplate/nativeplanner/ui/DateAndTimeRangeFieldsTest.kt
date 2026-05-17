package com.lss.onmyplate.nativeplanner.ui

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateAndTimeRangeFieldsTest {
    @Test
    fun parsesNumericTimeInputAsHourMinute() {
        assertEquals(LocalTime.of(9, 0), parseTimeDigitsOrNull("0900"))
        assertEquals(LocalTime.of(18, 30), parseTimeDigitsOrNull("1830"))
        assertEquals(LocalTime.of(9, 5), parseTimeDigitsOrNull("905"))
    }

    @Test
    fun rejectsOutOfRangeNumericTimeInput() {
        assertNull(parseTimeDigitsOrNull(""))
        assertNull(parseTimeDigitsOrNull("24"))
        assertNull(parseTimeDigitsOrNull("2460"))
        assertNull(parseTimeDigitsOrNull("1260"))
    }
}
