package com.lss.onmyplate.nativeplanner.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun DateTimePickerField(
    valueMillis: Long?,
    onValueChange: (Long?) -> Unit,
    label: String,
    required: Boolean = true,
) {
    val context = LocalContext.current
    val current = millisToLocalDateTime(valueMillis)

    fun showTimePicker(date: LocalDate) {
        val initialTime = current?.toLocalTime() ?: LocalTime.of(9, 0)
        TimePickerDialog(
            context,
            { _, hour, minute -> onValueChange(combineDateAndTime(date, LocalTime.of(hour, minute))) },
            initialTime.hour,
            initialTime.minute,
            true,
        ).show()
    }

    fun showDatePicker() {
        val initialDate = current?.toLocalDate() ?: LocalDate.now()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth -> showTimePicker(LocalDate.of(year, month + 1, dayOfMonth)) },
            initialDate.year,
            initialDate.monthValue - 1,
            initialDate.dayOfMonth,
        ).show()
    }

    Column {
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = formatDateTime(valueMillis),
                onValueChange = {},
                label = { Text(if (required) label else "$label (선택)") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FeedLoopColors.PrimaryDark,
                    unfocusedBorderColor = FeedLoopColors.Border,
                    focusedLabelColor = FeedLoopColors.PrimaryDark,
                    cursorColor = FeedLoopColors.PrimaryDark,
                ),
            )
            Box(Modifier.matchParentSize().clickable { showDatePicker() })
        }
        if (!required && valueMillis != null) {
            TextButton(onClick = { onValueChange(null) }) { Text("시간 지우기") }
        }
    }
}

@Composable
fun DateAndTimeRangeFields(
    startMillis: Long?,
    onStartChange: (Long?) -> Unit,
    endMillis: Long?,
    onEndChange: (Long?) -> Unit,
    requiredStart: Boolean = true,
) {
    val startDateTime = millisToLocalDateTime(startMillis)
    val endDateTime = millisToLocalDateTime(endMillis)
    var selectedDate by remember(startMillis, endMillis) {
        mutableStateOf(startDateTime?.toLocalDate() ?: endDateTime?.toLocalDate() ?: LocalDate.now())
    }
    var startTimeText by remember(startMillis) {
        mutableStateOf(startDateTime?.toLocalTime()?.toTimeText().orEmpty())
    }
    var endTimeText by remember(endMillis) {
        mutableStateOf(endDateTime?.toLocalTime()?.toTimeText().orEmpty())
    }

    fun applyDate(date: LocalDate) {
        selectedDate = date
        parseTimeTextOrNull(startTimeText)?.let { onStartChange(combineDateAndTime(date, it)) }
        parseTimeTextOrNull(endTimeText)?.let { onEndChange(combineDateAndTime(date, it)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DateOnlyPickerField(
            date = selectedDate,
            onDateChange = ::applyDate,
            label = if (requiredStart) "시작 날짜" else "시작 날짜 (선택)",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumericTimeField(
                value = startTimeText,
                onValueChange = { next ->
                    startTimeText = next
                    val parsed = parseTimeTextOrNull(next)
                    when {
                        parsed != null -> onStartChange(combineDateAndTime(selectedDate, parsed))
                        next.isBlank() -> onStartChange(null)
                    }
                },
                label = if (requiredStart) "시작 시간" else "시작 시간 (선택)",
                required = requiredStart,
                modifier = Modifier.weight(1f),
            )
            NumericTimeField(
                value = endTimeText,
                onValueChange = { next ->
                    endTimeText = next
                    val parsed = parseTimeTextOrNull(next)
                    if (parsed != null) {
                        onEndChange(combineDateAndTime(selectedDate, parsed))
                    } else if (next.isBlank()) {
                        onEndChange(null)
                    }
                },
                label = "끝 시간 (선택)",
                required = false,
                modifier = Modifier.weight(1f),
            )
        }
        if (!requiredStart && (startMillis != null || endMillis != null)) {
            TextButton(onClick = {
                startTimeText = ""
                endTimeText = ""
                onStartChange(null)
                onEndChange(null)
            }) { Text("시간 지우기") }
        }
    }
}

@Composable
private fun DateOnlyPickerField(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    label: String,
) {
    val context = LocalContext.current

    fun showDatePicker() {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth -> onDateChange(LocalDate.of(year, month + 1, dayOfMonth)) },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth,
        ).show()
    }

    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date.toString(),
            onValueChange = {},
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            colors = pickerFieldColors(),
        )
        Box(Modifier.matchParentSize().clickable { showDatePicker() })
    }
}

@Composable
private fun NumericTimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    required: Boolean,
    modifier: Modifier = Modifier,
) {
    val isInvalid = value.isNotBlank() && !isPotentialTimeText(value)
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            onValueChange(next.toEditableTimeText())
        },
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        placeholder = { Text("09:00") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        isError = isInvalid || (required && value.isBlank()),
        supportingText = {
            if (isInvalid) Text("00:00~23:59")
        },
        colors = pickerFieldColors(),
    )
}

@Composable
private fun pickerFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = FeedLoopColors.PrimaryDark,
    unfocusedBorderColor = FeedLoopColors.Border,
    focusedLabelColor = FeedLoopColors.PrimaryDark,
    cursorColor = FeedLoopColors.PrimaryDark,
    errorBorderColor = FeedLoopColors.Error,
)

internal fun parseTimeTextOrNull(value: String): LocalTime? {
    val trimmed = value.trim()
    if (trimmed.contains(':')) {
        val parts = trimmed.split(':')
        if (parts.size != 2) return null
        val hourText = parts[0]
        val minuteText = parts[1]
        if (hourText.length !in 1..2 || minuteText.length != 2) return null
        return parseHourMinuteOrNull(hourText, minuteText)
    }

    val digits = trimmed.filter(Char::isDigit)
    if (digits.length != 4 || digits.length != trimmed.length) return null
    return parseHourMinuteOrNull(digits.take(2), digits.takeLast(2))
}

private fun isPotentialTimeText(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.count { it == ':' } > 1) return false
    if (trimmed.any { !it.isDigit() && it != ':' }) return false

    if (!trimmed.contains(':')) {
        if (trimmed.length > 4) return false
        if (trimmed.length < 4) return true
        return parseTimeTextOrNull(trimmed) != null
    }

    val parts = trimmed.split(':')
    if (parts.size != 2) return false
    val hourText = parts[0]
    val minuteText = parts[1]
    if (hourText.isEmpty() || hourText.length > 2 || minuteText.length > 2) return false
    val hour = hourText.toIntOrNull() ?: return false
    if (hour !in 0..23) return false
    if (minuteText.length < 2) return true
    val minute = minuteText.toIntOrNull() ?: return false
    return minute in 0..59
}

private fun parseHourMinuteOrNull(hourText: String, minuteText: String): LocalTime? {
    val hour = hourText.toIntOrNull() ?: return null
    val minute = minuteText.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

private fun String.toEditableTimeText(): String {
    val filtered = filter { it.isDigit() || it == ':' }
    val colonIndex = filtered.indexOf(':')
    if (colonIndex < 0) return filtered.filter(Char::isDigit).take(4)

    val hour = filtered.take(colonIndex).filter(Char::isDigit).take(2)
    val minute = filtered.drop(colonIndex + 1).filter(Char::isDigit).take(2)
    return "$hour:$minute"
}

private fun LocalTime.toTimeText(): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
