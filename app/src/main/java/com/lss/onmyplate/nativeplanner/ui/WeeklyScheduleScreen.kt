package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val scheduleZone = ZoneId.of("Asia/Seoul")
private val dayLabelFormatter = DateTimeFormatter.ofPattern("M/d")
private val weekdayFormatter = DateTimeFormatter.ofPattern("E")

@Composable
fun WeeklyScheduleScreen(repository: PlannerRepository, onOpenSchedule: (String) -> Unit) {
    val schedules by repository.observeSchedules().collectAsState(initial = emptyList())
    val today = remember { LocalDate.now(scheduleZone) }
    val days = remember(today) { (0L..6L).map { today.plusDays(it) } }
    val visibleSchedules = remember(schedules, days) {
        val start = days.first().atStartOfDay(scheduleZone).toInstant().toEpochMilli()
        val end = days.last().plusDays(1).atStartOfDay(scheduleZone).toInstant().toEpochMilli()
        schedules.filter { it.startAt in start until end }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeedLoopColors.Background, FeedLoopColors.PrimaryLight.copy(alpha = 0.45f))))
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("7일 일정 노트", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("오늘부터 일주일 일정을 위젯처럼 한눈에 확인하고 눌러서 수정해요.", color = FeedLoopColors.Secondary)
                }
            }
            items(days, key = { it.toString() }) { day ->
                DayScheduleCard(day = day, schedules = visibleSchedules.filter { it.localDate() == day }, onOpenSchedule = onOpenSchedule)
            }
        }
    }
}

@Composable
private fun DayScheduleCard(day: LocalDate, schedules: List<ScheduleEntity>, onOpenSchedule: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = FeedLoopCardColors(),
        border = BorderStroke(1.dp, FeedLoopColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dayLabelFormatter.format(day), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FeedLoopColors.PrimaryDark)
                Text(weekdayFormatter.format(day), color = FeedLoopColors.Secondary)
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text("${schedules.size}개") })
            }
            if (schedules.isEmpty()) {
                Text("일정 없음", color = FeedLoopColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
            } else {
                schedules.sortedBy { it.startAt }.forEach { schedule ->
                    ScheduleSummaryRow(schedule = schedule, onClick = { onOpenSchedule(schedule.id) })
                }
            }
        }
    }
}

@Composable
private fun ScheduleSummaryRow(schedule: ScheduleEntity, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Elevated),
        border = BorderStroke(1.dp, FeedLoopColors.BorderMuted),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp).background(FeedLoopColors.PendingBg, MaterialTheme.shapes.medium).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(formatTime(schedule.startAt), color = FeedLoopColors.Pending, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(schedule.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val meta = listOfNotNull(schedule.location, statusDbLabel(schedule.status)).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
            Text("수정", color = FeedLoopColors.PrimaryDark, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun ScheduleEntity.localDate(): LocalDate = Instant.ofEpochMilli(startAt).atZone(scheduleZone).toLocalDate()
