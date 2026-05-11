package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceRuleEntity
import com.lss.onmyplate.nativeplanner.data.repository.RecurrenceInput

enum class RecurrenceMode {
    None,
    Daily,
    Weekly,
    Monthly,
    Custom,
}

enum class RecurrenceUnit {
    Day,
    Week,
    Month,
}

data class RecurrenceUiState(
    val mode: RecurrenceMode = RecurrenceMode.None,
    val customInterval: String = "2",
    val customUnit: RecurrenceUnit = RecurrenceUnit.Week,
    val untilAt: Long? = null,
)

@Composable
fun RecurrenceControls(
    state: RecurrenceUiState,
    onStateChange: (RecurrenceUiState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("반복", style = MaterialTheme.typography.bodyLarge)
        recurrenceModes.chunked(3).forEach { rowModes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowModes.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onStateChange(state.copy(mode = mode)) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FeedLoopColors.PrimaryLight,
                            selectedLabelColor = FeedLoopColors.PrimaryDark,
                            containerColor = FeedLoopColors.Surface,
                        ),
                    )
                }
            }
        }
        if (state.mode == RecurrenceMode.Custom) {
            OutlinedTextField(
                value = state.customInterval,
                onValueChange = { value -> onStateChange(state.copy(customInterval = value.filter(Char::isDigit))) },
                label = { Text("반복 간격") },
                suffix = { Text("마다") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.customInterval.toIntOrNull()?.let { it < 1 } ?: true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FeedLoopColors.PrimaryDark,
                    unfocusedBorderColor = FeedLoopColors.Border,
                    focusedLabelColor = FeedLoopColors.PrimaryDark,
                    cursorColor = FeedLoopColors.PrimaryDark,
                    errorBorderColor = FeedLoopColors.Error,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecurrenceUnit.entries.forEach { unit ->
                    FilterChip(
                        selected = state.customUnit == unit,
                        onClick = { onStateChange(state.copy(customUnit = unit)) },
                        label = { Text(unit.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FeedLoopColors.PendingBg,
                            selectedLabelColor = FeedLoopColors.Pending,
                            containerColor = FeedLoopColors.Surface,
                        ),
                    )
                }
            }
        }
        if (state.mode != RecurrenceMode.None) {
            DateTimePickerField(state.untilAt, { onStateChange(state.copy(untilAt = it)) }, "반복 종료", required = false)
        }
    }
}

fun RecurrenceUiState.toRecurrenceInput(): RecurrenceInput? =
    when (mode) {
        RecurrenceMode.None -> RecurrenceInput.None
        RecurrenceMode.Daily -> RecurrenceInput.Daily(untilAt = untilAt)
        RecurrenceMode.Weekly -> RecurrenceInput.Weekly(untilAt = untilAt)
        RecurrenceMode.Monthly -> RecurrenceInput.Monthly(untilAt = untilAt)
        RecurrenceMode.Custom -> {
            val interval = customInterval.toIntOrNull()?.takeIf { it >= 1 } ?: return null
            when (customUnit) {
                RecurrenceUnit.Day -> RecurrenceInput.Daily(intervalDays = interval, untilAt = untilAt)
                RecurrenceUnit.Week -> RecurrenceInput.Weekly(intervalWeeks = interval, untilAt = untilAt)
                RecurrenceUnit.Month -> RecurrenceInput.Monthly(intervalMonths = interval, untilAt = untilAt)
            }
        }
    }

fun ScheduleRecurrenceRuleEntity.toRecurrenceUiState(): RecurrenceUiState {
    val intervalText = interval.coerceAtLeast(1).toString()
    return when (frequency) {
        "daily" -> if (interval == 1) {
            RecurrenceUiState(mode = RecurrenceMode.Daily, untilAt = untilAt)
        } else {
            RecurrenceUiState(mode = RecurrenceMode.Custom, customInterval = intervalText, customUnit = RecurrenceUnit.Day, untilAt = untilAt)
        }
        "weekly" -> if (interval == 1) {
            RecurrenceUiState(mode = RecurrenceMode.Weekly, untilAt = untilAt)
        } else {
            RecurrenceUiState(mode = RecurrenceMode.Custom, customInterval = intervalText, customUnit = RecurrenceUnit.Week, untilAt = untilAt)
        }
        "monthly" -> if (interval == 1) {
            RecurrenceUiState(mode = RecurrenceMode.Monthly, untilAt = untilAt)
        } else {
            RecurrenceUiState(mode = RecurrenceMode.Custom, customInterval = intervalText, customUnit = RecurrenceUnit.Month, untilAt = untilAt)
        }
        else -> RecurrenceUiState()
    }
}

private val recurrenceModes = listOf(
    RecurrenceMode.None,
    RecurrenceMode.Daily,
    RecurrenceMode.Weekly,
    RecurrenceMode.Monthly,
    RecurrenceMode.Custom,
)

private val RecurrenceMode.label: String
    get() = when (this) {
        RecurrenceMode.None -> "반복 안함"
        RecurrenceMode.Daily -> "매일"
        RecurrenceMode.Weekly -> "매주"
        RecurrenceMode.Monthly -> "매월"
        RecurrenceMode.Custom -> "맞춤 설정"
    }

private val RecurrenceUnit.label: String
    get() = when (this) {
        RecurrenceUnit.Day -> "일"
        RecurrenceUnit.Week -> "주"
        RecurrenceUnit.Month -> "월"
    }
