package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
) {
    val candidate by repository.observeCandidate(candidateId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var title by remember(candidate?.id) { mutableStateOf(candidate?.extractedTitle.orEmpty()) }
    var startAt by remember(candidate?.id) { mutableStateOf(formatDateTime(candidate?.extractedStartAt)) }
    var endAt by remember(candidate?.id) { mutableStateOf(formatDateTime(candidate?.extractedEndAt)) }
    var location by remember(candidate?.id) { mutableStateOf(candidate?.extractedLocation.orEmpty()) }
    var status by remember { mutableStateOf(ScheduleStatus.Confirmed) }
    val canSave = title.isNotBlank()

    if (candidate == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("후보를 찾을 수 없습니다") }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("약속 후보 수정", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(title, { title = it }, label = { Text("제목") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(startAt, { startAt = it }, label = { Text("시작 yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(endAt, { endAt = it }, label = { Text("종료 yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(location, { location = it }, label = { Text("장소") }, modifier = Modifier.fillMaxWidth())
        StatusSelector(status = status, onStatus = { status = it })
        Text("원문", style = MaterialTheme.typography.titleMedium)
        Text(candidate!!.rawText, style = MaterialTheme.typography.bodyMedium)
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
            modifier = Modifier.fillMaxWidth(),
            enabled = canSave,
        ) { Text("저장") }
    }
}

@Composable
private fun StatusSelector(status: ScheduleStatus, onStatus: (ScheduleStatus) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScheduleStatus.entries.forEach {
            FilterChip(
                selected = status == it,
                onClick = { onStatus(it) },
                label = {
                    Text(
                        when (it) {
                            ScheduleStatus.Confirmed -> "confirmed"
                            ScheduleStatus.Planned -> "planned"
                            ScheduleStatus.Uncertain -> "uncertain"
                        },
                    )
                },
            )
        }
    }
}
