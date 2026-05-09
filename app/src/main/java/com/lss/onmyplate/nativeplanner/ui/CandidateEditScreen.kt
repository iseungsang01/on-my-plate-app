package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.repository.SaveResult
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import kotlinx.coroutines.launch

@Composable
fun CandidateEditScreen(
    repository: PlannerRepository,
    candidateId: String,
    onDone: () -> Unit,
    onConflict: () -> Unit,
    onBack: () -> Unit = onDone,
) {
    val candidate by repository.observeCandidate(candidateId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var title by remember(candidate?.id) { mutableStateOf(candidate?.extractedTitle.orEmpty()) }
    var startAt by remember(candidate?.id) { mutableStateOf(formatDateTime(candidate?.extractedStartAt)) }
    var endAt by remember(candidate?.id) { mutableStateOf(formatDateTime(candidate?.extractedEndAt)) }
    var location by remember(candidate?.id) { mutableStateOf(candidate?.extractedLocation.orEmpty()) }
    var status by remember(candidate?.id) { mutableStateOf(ScheduleStatus.Confirmed) }
    val canSave = title.isNotBlank()

    if (candidate == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("약속 후보를 찾을 수 없습니다") }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF8FAFF), Color(0xFFFFFFFF))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("‹") }
            Text("약속 후보 상세", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = {}) { Text("⋮") }
        }

        AssistChip(
            onClick = {},
            label = { Text(if (candidate!!.timeConfidence == "high") "확정 가능" else "정보 보완 필요") },
            colors = AssistChipDefaults.assistChipColors(containerColor = FeedLoopColors.SuccessBg, labelColor = FeedLoopColors.Success),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = FeedLoopColors.SuccessBorder),
        )

        Text(title.ifBlank { "제목 입력 필요" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        InfoBlock {
            BasketTextField(title, { title = it }, "제목")
            BasketTextField(startAt, { startAt = it }, "시작 yyyy-MM-dd HH:mm")
            BasketTextField(endAt, { endAt = it }, "종료 yyyy-MM-dd HH:mm")
            BasketTextField(location, { location = it }, "장소")
        }

        Text("메모", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F7FB)), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
            Text(candidate!!.rawText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp), color = FeedLoopColors.Secondary)
        }

        Text("신뢰도", style = MaterialTheme.typography.labelLarge, color = FeedLoopColors.Secondary)
        LinearProgressIndicator(progress = { candidate!!.confidence.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = FeedLoopColors.Success, trackColor = FeedLoopColors.BorderMuted)
        Text("${(candidate!!.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = FeedLoopColors.Secondary)

        StatusSelector(status = status, onStatus = { status = it })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, FeedLoopColors.Border)) { Text("수정 취소") }
            Button(
                onClick = {
                    scope.launch {
                        repository.updateCandidate(candidateId, title, parseDateTimeOrNull(startAt), parseDateTimeOrNull(endAt), location)
                        when (repository.saveFromCandidate(candidateId, status, title)) {
                            is SaveResult.Conflict -> onConflict()
                            else -> onDone()
                        }
                    }
                },
                modifier = Modifier.weight(1.2f),
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
            ) { Text("일정에 추가") }
        }
    }
}

@Composable
private fun InfoBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface), border = BorderStroke(1.dp, FeedLoopColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun BasketTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = FeedLoopColors.PrimaryDark, unfocusedBorderColor = FeedLoopColors.Border, focusedLabelColor = FeedLoopColors.PrimaryDark, cursorColor = FeedLoopColors.PrimaryDark),
    )
}

@Composable
private fun StatusSelector(status: ScheduleStatus, onStatus: (ScheduleStatus) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScheduleStatus.entries.forEach {
            FilterChip(
                selected = status == it,
                onClick = { onStatus(it) },
                label = { Text(statusLabel(it)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FeedLoopColors.PendingBg, selectedLabelColor = FeedLoopColors.Pending, containerColor = FeedLoopColors.Surface),
            )
        }
    }
}

fun statusLabel(status: ScheduleStatus): String = when (status) {
    ScheduleStatus.Confirmed -> "확정"
    ScheduleStatus.Planned -> "예정"
    ScheduleStatus.Uncertain -> "보류"
}
