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
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(FeedLoopColors.Background, FeedLoopColors.PrimaryLight.copy(alpha = 0.28f)),
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("약속 후보 정리", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Elevated),
            border = BorderStroke(1.dp, FeedLoopColors.Border),
            elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasketTextField(title, { title = it }, "제목")
                BasketTextField(startAt, { startAt = it }, "시작 yyyy-MM-dd HH:mm")
                BasketTextField(endAt, { endAt = it }, "종료 yyyy-MM-dd HH:mm")
                BasketTextField(location, { location = it }, "장소")
                StatusSelector(status = status, onStatus = { status = it })
            }
        }
        Text("원문", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(
            Modifier.fillMaxWidth(),
            colors = FeedLoopCardColors(),
            border = BorderStroke(1.dp, FeedLoopColors.Border),
            elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
        ) {
            Text(candidate!!.rawText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
        }
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
            colors = ButtonDefaults.buttonColors(
                containerColor = FeedLoopColors.Primary,
                disabledContainerColor = FeedLoopColors.BorderMuted,
                disabledContentColor = FeedLoopColors.TextMuted,
            ),
        ) { Text("저장") }
    }
}

@Composable
private fun BasketTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = FeedLoopColors.Primary,
            unfocusedBorderColor = FeedLoopColors.Border,
            focusedLabelColor = FeedLoopColors.PrimaryDark,
            cursorColor = FeedLoopColors.PrimaryDark,
        ),
    )
}

@Composable
private fun StatusSelector(status: ScheduleStatus, onStatus: (ScheduleStatus) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScheduleStatus.entries.forEach {
            val selectedColor = when (it) {
                ScheduleStatus.Confirmed -> FeedLoopColors.Success
                ScheduleStatus.Planned -> FeedLoopColors.Warning
                ScheduleStatus.Uncertain -> FeedLoopColors.Pending
            }
            val selectedBg = when (it) {
                ScheduleStatus.Confirmed -> FeedLoopColors.SuccessBg
                ScheduleStatus.Planned -> FeedLoopColors.WarningBg
                ScheduleStatus.Uncertain -> FeedLoopColors.PendingBg
            }
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
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = selectedBg,
                    selectedLabelColor = selectedColor,
                    labelColor = FeedLoopColors.Secondary,
                    containerColor = FeedLoopColors.Surface,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = status == it,
                    borderColor = FeedLoopColors.Border,
                    selectedBorderColor = selectedColor,
                ),
            )
        }
    }
}
