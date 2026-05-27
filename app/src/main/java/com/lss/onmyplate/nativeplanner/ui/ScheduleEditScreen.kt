package com.lss.onmyplate.nativeplanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import kotlinx.coroutines.launch

@Composable
fun ScheduleEditScreen(
    repository: PlannerRepository,
    scheduleId: String,
    occurrenceStartAt: Long? = null,
    onBack: () -> Unit,
) {
    val schedule by repository.observeSchedule(scheduleId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var title by remember(schedule?.id) { mutableStateOf(schedule?.title.orEmpty()) }
    var startAt by remember(schedule?.id) { mutableStateOf(schedule?.startAt) }
    var endAt by remember(schedule?.id) { mutableStateOf(schedule?.endAt) }
    var location by remember(schedule?.id) { mutableStateOf(schedule?.location.orEmpty()) }
    var memo by remember(schedule?.id) { mutableStateOf(schedule?.memo.orEmpty()) }
    var status by remember(schedule?.id) { mutableStateOf(scheduleStatusFromDb(schedule?.status)) }
    var recurrenceState by remember(schedule?.id) { mutableStateOf(RecurrenceUiState()) }
    var message by remember { mutableStateOf<String?>(null) }
    var actionInFlight by remember { mutableStateOf(false) }
    val recurrenceInput = recurrenceState.toRecurrenceInput()
    val canSave = !actionInFlight && title.isNotBlank() && startAt != null && recurrenceInput != null
    val deleteWholeSchedule: () -> Unit = {
        scope.launch {
            actionInFlight = true
            message = null
            runCatching {
                repository.deleteSchedule(scheduleId)
            }.onSuccess {
                onBack()
            }.onFailure {
                message = "일정 삭제에 실패했습니다. 네트워크를 확인해주세요."
                actionInFlight = false
            }
        }
    }

    BackHandler { onBack() }

    LaunchedEffect(schedule?.id) {
        val loadedSchedule = schedule ?: return@LaunchedEffect
        val rule = repository.getRecurrenceRule(loadedSchedule.id)
        recurrenceState = rule?.toRecurrenceUiState() ?: RecurrenceUiState()
    }

    if (schedule == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("일정을 찾을 수 없습니다.")
                TextButton(onClick = onBack) { Text("← 돌아가기") }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeedLoopColors.Background, FeedLoopColors.Surface)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, enabled = !actionInFlight) { Text("← 일정") }
            Text(
                "일정 수정",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = deleteWholeSchedule, enabled = !actionInFlight) {
                Text("삭제", color = FeedLoopColors.Error, style = MaterialTheme.typography.labelLarge)
            }
        }

        Card(Modifier.fillMaxWidth(), colors = FeedLoopCardColors(), border = BorderStroke(1.dp, FeedLoopColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlannerTextField(title, { title = it }, "제목")
                DateAndTimeRangeFields(
                    startMillis = startAt,
                    onStartChange = { startAt = it },
                    endMillis = endAt,
                    onEndChange = { endAt = it },
                )
                PlannerTextField(location, { location = it }, "장소", required = false)
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("메모") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = FeedLoopColors.PrimaryDark, unfocusedBorderColor = FeedLoopColors.Border, focusedLabelColor = FeedLoopColors.PrimaryDark, cursorColor = FeedLoopColors.PrimaryDark),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleStatus.entries.forEach { item ->
                        FilterChip(
                            selected = status == item,
                            onClick = { status = item },
                            label = { Text(statusLabel(item), style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FeedLoopColors.PendingBg, selectedLabelColor = FeedLoopColors.Pending, containerColor = FeedLoopColors.Surface),
                        )
                    }
                }
                RecurrenceControls(recurrenceState, { recurrenceState = it })
            }
        }

        message?.let { Text(it, color = FeedLoopColors.Error) }

        if (occurrenceStartAt != null && recurrenceState.mode != RecurrenceMode.None) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        actionInFlight = true
                        message = null
                        runCatching {
                            repository.skipRecurringOccurrence(scheduleId, occurrenceStartAt)
                        }.onSuccess {
                            onBack()
                        }.onFailure {
                            message = "이번 반복 일정 삭제에 실패했습니다. 네트워크를 확인해주세요."
                            actionInFlight = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, FeedLoopColors.WarningBorder),
                enabled = !actionInFlight,
            ) {
                Text("이번 일정만 삭제")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, FeedLoopColors.Border), enabled = !actionInFlight) { Text("취소") }
            Button(
                onClick = {
                    scope.launch {
                        actionInFlight = true
                        message = null
                        runCatching {
                            repository.updateSchedule(
                                scheduleId = scheduleId,
                                title = title,
                                startAt = startAt,
                                endAt = endAt,
                                location = location,
                                memo = memo,
                                status = status,
                                recurrenceInput = recurrenceInput ?: return@launch,
                            )
                        }.onSuccess { ok ->
                            if (ok) {
                                onBack()
                            } else {
                                message = "제목과 시작 시간을 확인해주세요."
                                actionInFlight = false
                            }
                        }.onFailure {
                            message = "일정 저장에 실패했습니다. 네트워크를 확인해주세요."
                            actionInFlight = false
                        }
                    }
                },
                modifier = Modifier.weight(1.2f),
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
            ) { Text("저장") }
        }
    }
}

@Composable
private fun PlannerTextField(value: String, onValueChange: (String) -> Unit, label: String, required: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (required) label else "$label (선택)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = FeedLoopColors.PrimaryDark, unfocusedBorderColor = FeedLoopColors.Border, focusedLabelColor = FeedLoopColors.PrimaryDark, cursorColor = FeedLoopColors.PrimaryDark),
    )
}

fun scheduleStatusFromDb(value: String?): ScheduleStatus = when (value) {
    ScheduleStatus.Confirmed.dbValue -> ScheduleStatus.Confirmed
    else -> ScheduleStatus.Uncertain
}

fun statusDbLabel(value: String): String = statusLabel(scheduleStatusFromDb(value))
