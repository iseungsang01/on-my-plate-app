package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import kotlinx.coroutines.launch

@Composable
fun PlannerScreen(repository: PlannerRepository, onOpenCandidate: (String) -> Unit) {
    val schedules by repository.observeSchedules().collectAsState(initial = emptyList())
    val pending by repository.observePendingCandidates().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var directInput by remember { mutableStateOf("") }
    var isCreatingCandidate by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Planner", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("직접 입력", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = directInput,
                    onValueChange = { directInput = it },
                    label = { Text("약속 메시지 붙여넣기 또는 입력") },
                    placeholder = { Text("예: 내일 오후 3시 강남역에서 민수와 커피") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !isCreatingCandidate,
                )
                Button(
                    onClick = {
                        val rawText = directInput.trim()
                        if (rawText.isBlank()) return@Button
                        scope.launch {
                            isCreatingCandidate = true
                            try {
                                val candidate = repository.createCandidate(
                                    rawText = rawText,
                                    sourceApp = "internal",
                                    receivedAt = System.currentTimeMillis(),
                                )
                                directInput = ""
                                onOpenCandidate(candidate.id)
                            } finally {
                                isCreatingCandidate = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = directInput.isNotBlank() && !isCreatingCandidate,
                ) {
                    Text(if (isCreatingCandidate) "분석 중..." else "후보 만들기")
                }
            }
        }
        if (pending.isNotEmpty()) {
            Text("미정 후보", style = MaterialTheme.typography.titleMedium)
            pending.forEach {
                AssistChip(onClick = { onOpenCandidate(it.id) }, label = { Text(it.extractedTitle.ifBlank { "제목 입력 필요" }) })
            }
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            schedules.groupBy { formatDay(it.startAt) }.forEach { (day, dayItems) ->
                item { Text(day, style = MaterialTheme.typography.titleMedium) }
                items(dayItems, key = { it.id }) { ScheduleRow(it) }
            }
        }
    }
}

@Composable
private fun ScheduleRow(schedule: ScheduleEntity) {
    val color = when (schedule.status) {
        "confirmed" -> Color(0xFF1B7F46)
        "planned" -> Color(0xFF8A6D00)
        else -> Color(0xFF757575)
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTime(schedule.startAt), color = color, modifier = Modifier.width(54.dp))
            Column(Modifier.weight(1f)) {
                Text(schedule.title, style = MaterialTheme.typography.titleMedium)
                val meta = listOfNotNull(schedule.location, schedule.status).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = Color(0xFF616161))
            }
        }
    }
}
