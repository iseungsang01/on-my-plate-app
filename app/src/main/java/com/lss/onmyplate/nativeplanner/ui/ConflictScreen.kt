package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.repository.SaveAttempt
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import kotlinx.coroutines.launch

@Composable
fun ConflictScreen(
    repository: PlannerRepository,
    candidateId: String,
    onEdit: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var conflicts by remember { mutableStateOf<List<ScheduleEntity>>(emptyList()) }
    val candidate by repository.observeCandidate(candidateId).collectAsState(initial = null)
    val hasTitle = candidate?.extractedTitle?.isNotBlank() == true
    var selectedOption by remember { mutableStateOf("time") }

    LaunchedEffect(candidateId) {
        conflicts = when (val attempt = repository.conflictsForCandidate(candidateId)) {
            is SaveAttempt.Conflict -> attempt.conflicts
            else -> emptyList()
        }
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
            TextButton(onClick = onEdit) { Text("‹") }
            Text("일정 충돌", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = {}) { Text("⋮") }
        }

        AssistChip(
            onClick = {},
            label = { Text("기존 일정과 시간이 겹쳐요") },
            colors = AssistChipDefaults.assistChipColors(containerColor = FeedLoopColors.WarningBg, labelColor = FeedLoopColors.Warning),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = FeedLoopColors.WarningBorder),
        )

        Text(candidate?.extractedTitle?.takeIf { it.isNotBlank() } ?: "제목 입력 필요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionTitle("겹치는 일정")
        conflicts.forEach { ConflictCard(it) }
        if (conflicts.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
                Text("현재 조회된 충돌 일정이 없습니다.", modifier = Modifier.padding(14.dp), color = FeedLoopColors.Secondary)
            }
        }

        SectionTitle("옵션")
        ConflictOption("time", "시간 변경 후 추가", selectedOption) { selectedOption = it }
        ConflictOption("adjust", "겹치는 일정 조정", selectedOption) { selectedOption = it }
        ConflictOption("hold", "보류하기", selectedOption) { selectedOption = it }
        ConflictOption("force", "추가하지 않음", selectedOption) { selectedOption = it }

        Spacer(Modifier.weight(1f, fill = false))
        Button(
            onClick = {
                when (selectedOption) {
                    "time", "adjust" -> onEdit()
                    "hold" -> scope.launch {
                        repository.saveFromCandidate(candidateId, ScheduleStatus.Uncertain, candidate?.extractedTitle)
                        onDone()
                    }
                    else -> scope.launch {
                        repository.discardCandidate(candidateId)
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = hasTitle,
            colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
        ) { Text(if (selectedOption == "time" || selectedOption == "adjust") "시간 변경하고 추가" else "선택 완료") }

        OutlinedButton(
            onClick = {
                scope.launch {
                    repository.saveFromCandidate(candidateId, ScheduleStatus.Confirmed, candidate?.extractedTitle, force = true)
                    onDone()
                }
            },
            enabled = hasTitle,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, FeedLoopColors.WarningBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = FeedLoopColors.Warning),
        ) { Text("그래도 일정에 추가") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ConflictCard(schedule: ScheduleEntity) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEFF1)), border = BorderStroke(1.dp, FeedLoopColors.ErrorBorder), elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(schedule.title, style = MaterialTheme.typography.titleMedium, color = FeedLoopColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text("${formatDateTime(schedule.startAt)} - ${formatTime(schedule.endAt ?: schedule.startAt)}", color = FeedLoopColors.Secondary)
            Text(schedule.location ?: "장소 정보 없음", color = FeedLoopColors.Secondary)
        }
    }
}

@Composable
private fun ConflictOption(value: String, label: String, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected = selected == value, onClick = { onSelected(value) }, colors = RadioButtonDefaults.colors(selectedColor = FeedLoopColors.PrimaryDark))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
