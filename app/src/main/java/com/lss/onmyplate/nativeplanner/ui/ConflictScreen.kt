package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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

    LaunchedEffect(candidateId) {
        conflicts = when (val attempt = repository.conflictsForCandidate(candidateId)) {
            is SaveAttempt.Conflict -> attempt.conflicts
            else -> emptyList()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeedLoopColors.ErrorBg, FeedLoopColors.Background)))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("일정이 겹칩니다", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = FeedLoopColors.Error)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
            border = BorderStroke(1.dp, FeedLoopColors.ErrorBorder),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("새 후보", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.labelLarge)
                Text(candidate?.extractedTitle?.takeIf { it.isNotBlank() } ?: "제목 입력 필요", style = MaterialTheme.typography.titleMedium)
                Text("${formatDateTime(candidate?.extractedStartAt)} ${candidate?.extractedLocation.orEmpty()}", color = FeedLoopColors.Secondary)
            }
        }
        Text("겹치는 일정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        conflicts.forEach {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = FeedLoopColors.ErrorBg),
                border = BorderStroke(1.dp, FeedLoopColors.ErrorBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(it.title, style = MaterialTheme.typography.titleMedium, color = FeedLoopColors.Error)
                    Text("${formatDateTime(it.startAt)} - ${formatDateTime(it.endAt)}", color = FeedLoopColors.Secondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch {
                    repository.saveFromCandidate(candidateId, ScheduleStatus.Confirmed, candidate?.extractedTitle, force = true)
                    onDone()
                }
            }, enabled = hasTitle, colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.Primary)) { Text("그래도 추가") }
            OutlinedButton(
                onClick = onEdit,
                border = BorderStroke(1.dp, FeedLoopColors.Primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FeedLoopColors.PrimaryDark),
            ) { Text("수정하기") }
            OutlinedButton(onClick = {
                scope.launch {
                    repository.discardCandidate(candidateId)
                    onDone()
                }
            }, border = BorderStroke(1.dp, FeedLoopColors.ErrorBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = FeedLoopColors.Error)) { Text("취소") }
        }
    }
}
