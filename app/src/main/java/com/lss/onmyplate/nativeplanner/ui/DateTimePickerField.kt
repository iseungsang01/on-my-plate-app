package com.lss.onmyplate.nativeplanner.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
