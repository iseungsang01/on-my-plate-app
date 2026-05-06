package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository

@Composable
fun PlannerScreen(repository: PlannerRepository, onOpenCandidate: (String) -> Unit) {
    val schedules by repository.observeSchedules().collectAsState(initial = emptyList())
    val pending by repository.observePendingCandidates().collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Planner", style = MaterialTheme.typography.headlineMedium)
        if (pending.isNotEmpty()) {
            Text("미정 후보", style = MaterialTheme.typography.titleMedium)
            pending.forEach {
                AssistChip(onClick = { onOpenCandidate(it.id) }, label = { Text(it.extractedTitle) })
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
