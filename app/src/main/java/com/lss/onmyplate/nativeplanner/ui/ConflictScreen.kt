package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("일정이 겹칩니다", style = MaterialTheme.typography.headlineMedium)
        Text("새 후보: ${candidate?.extractedTitle?.takeIf { it.isNotBlank() } ?: "제목 입력 필요"}")
        Text("${formatDateTime(candidate?.extractedStartAt)} ${candidate?.extractedLocation.orEmpty()}")
        Text("겹치는 일정", style = MaterialTheme.typography.titleMedium)
        conflicts.forEach {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(it.title, style = MaterialTheme.typography.titleMedium)
                    Text("${formatDateTime(it.startAt)} - ${formatDateTime(it.endAt)}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    repository.saveFromCandidate(candidateId, ScheduleStatus.Confirmed, candidate?.extractedTitle, force = true)
                    onDone()
                }
            }, enabled = hasTitle) { Text("그래도 추가") }
            OutlinedButton(onClick = onEdit) { Text("수정하기") }
            OutlinedButton(onClick = {
                scope.launch {
                    repository.discardCandidate(candidateId)
                    onDone()
                }
            }) { Text("취소") }
        }
    }
}
