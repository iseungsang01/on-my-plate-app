package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import kotlinx.coroutines.launch

@Composable
fun PlannerScreen(repository: PlannerRepository, onOpenCandidate: (String) -> Unit, onOpenSharing: () -> Unit) {
    val schedules by repository.observeSchedules().collectAsState(initial = emptyList())
    val pending by repository.observePendingCandidates().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var directInput by remember { mutableStateOf("") }
    var isCreatingCandidate by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(FeedLoopColors.Background, FeedLoopColors.PrimaryLight.copy(alpha = 0.35f)),
                ),
            ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "?? ???",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = FeedLoopColors.TextPrimary,
                    )
                    OutlinedButton(onClick = onOpenSharing) { Text("??") }
                }
                Text(
                    "??? ? ??? ????, ?? ?? ???? ?????.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FeedLoopColors.Secondary,
                )
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Elevated),
                border = BorderStroke(1.dp, FeedLoopColors.Border),
                elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("새 약속 담기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = directInput,
                        onValueChange = { directInput = it },
                        label = { Text("약속 메시지 붙여넣기 또는 입력") },
                        placeholder = { Text("예: 내일 오후 3시 강남역에서 민수와 커피") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        enabled = !isCreatingCandidate,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FeedLoopColors.Primary,
                            unfocusedBorderColor = FeedLoopColors.Border,
                            focusedLabelColor = FeedLoopColors.PrimaryDark,
                            cursorColor = FeedLoopColors.PrimaryDark,
                        ),
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FeedLoopColors.Primary,
                            disabledContainerColor = Color(0xFFEFE7DA),
                            disabledContentColor = FeedLoopColors.TextMuted,
                        ),
                    ) {
                        Text(if (isCreatingCandidate) "분석 중..." else "후보 만들기")
                    }
                }
            }
            if (pending.isNotEmpty()) {
                Text("미정 후보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pending.forEach {
                        AssistChip(
                            onClick = { onOpenCandidate(it.id) },
                            label = { Text(it.extractedTitle.ifBlank { "제목 입력 필요" }) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = FeedLoopColors.WarningBg,
                                labelColor = FeedLoopColors.Warning,
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = FeedLoopColors.WarningBorder,
                            ),
                        )
                    }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (schedules.isEmpty()) {
                    item {
                        EmptyScheduleCard()
                    }
                }
                schedules.groupBy { formatDay(it.startAt) }.forEach { (day, dayItems) ->
                    item {
                        Text(
                            day,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = FeedLoopColors.TextPrimary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(dayItems, key = { it.id }) { ScheduleRow(it) }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(schedule: ScheduleEntity) {
    val statusColor = when (schedule.status) {
        "confirmed" -> FeedLoopColors.Success
        "planned" -> FeedLoopColors.Warning
        else -> FeedLoopColors.Pending
    }
    val statusBg = when (schedule.status) {
        "confirmed" -> FeedLoopColors.SuccessBg
        "planned" -> FeedLoopColors.WarningBg
        else -> FeedLoopColors.PendingBg
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = FeedLoopCardColors(),
        border = BorderStroke(1.dp, FeedLoopColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(58.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(statusBg)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(formatTime(schedule.startAt), color = statusColor, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(schedule.title, style = MaterialTheme.typography.titleMedium, color = FeedLoopColors.TextPrimary)
                val meta = listOfNotNull(schedule.location, schedule.status).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = FeedLoopColors.Secondary)
            }
        }
    }
}

@Composable
private fun EmptyScheduleCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Tertiary),
        border = BorderStroke(1.dp, FeedLoopColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("아직 담긴 일정이 없어요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("공유된 메시지나 직접 입력으로 첫 약속을 바구니에 담아보세요.", color = FeedLoopColors.Secondary)
        }
    }
}
