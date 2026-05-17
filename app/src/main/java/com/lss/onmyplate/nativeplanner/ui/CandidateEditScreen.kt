package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.repository.SaveResult
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseSourceValues
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
    var saveMode by remember(candidate?.id) { mutableStateOf(candidate.defaultSaveMode()) }
    var title by remember(candidate?.id) { mutableStateOf(candidate?.extractedTitle.orEmpty()) }
    var memo by remember(candidate?.id) { mutableStateOf("") }
    var startAt by remember(candidate?.id) { mutableStateOf(candidate?.extractedStartAt) }
    var endAt by remember(candidate?.id) { mutableStateOf(candidate?.extractedEndAt) }
    var location by remember(candidate?.id) { mutableStateOf(candidate?.extractedLocation.orEmpty()) }
    var recurrenceState by remember(candidate?.id) { mutableStateOf(RecurrenceUiState()) }
    var actionInFlight by remember(candidate?.id) { mutableStateOf(false) }
    var actionError by remember(candidate?.id) { mutableStateOf<String?>(null) }
    val recurrenceInput = recurrenceState.toRecurrenceInput()
    val canSave = !actionInFlight && recurrenceInput != null && (saveMode == CandidateSaveMode.Uncertain || title.isNotBlank())

    val currentCandidate = candidate
    if (currentCandidate == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("일정 디테일 설정을 찾을 수 없습니다.") }
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
            TextButton(onClick = onBack, enabled = !actionInFlight) { Text("← 설정 목록") }
            Text("일정 디테일 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                if (actionInFlight) return@TextButton
                actionInFlight = true
                actionError = null
                scope.launch {
                    runCatching {
                        repository.discardCandidate(candidateId)
                    }.onSuccess {
                        onBack()
                    }.onFailure {
                        actionError = "Unable to save. Check the network and try again."
                        actionInFlight = false
                    }
                }
            }, enabled = !actionInFlight) { Text("버리기") }
        }

        AssistChip(
            onClick = {},
            label = { Text(if (currentCandidate.timeConfidence == "high") "확정 전 설정" else "시간 정보 확인 필요") },
            colors = AssistChipDefaults.assistChipColors(containerColor = FeedLoopColors.SuccessBg, labelColor = FeedLoopColors.Success),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = FeedLoopColors.SuccessBorder),
        )

        Text(title.ifBlank { "미정 일정" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        InfoBlock {
            SaveModeSelector(saveMode, onModeChange = { saveMode = it })
            BasketTextField(
                value = if (saveMode == CandidateSaveMode.Confirmed) title else memo,
                onValueChange = {
                    if (saveMode == CandidateSaveMode.Confirmed) title = it else memo = it
                },
                label = if (saveMode == CandidateSaveMode.Confirmed) "일정 제목 작성" else "일정 메모 작성",
                accentColor = if (saveMode == CandidateSaveMode.Confirmed) WarmAccent else ColdAccent,
            )
            DateAndTimeRangeFields(
                startMillis = startAt,
                onStartChange = { startAt = it },
                endMillis = endAt,
                onEndChange = { endAt = it },
                requiredStart = false,
            )
            BasketTextField(location, { location = it }, "장소")
            RecurrenceControls(recurrenceState, { recurrenceState = it })
        }

        Text("원문", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F7FB)), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
            Text(currentCandidate.rawText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp), color = FeedLoopColors.Secondary)
        }

        Text(parseSourceLabel(currentCandidate.parseSource), style = MaterialTheme.typography.bodySmall, color = FeedLoopColors.Secondary)
        Text("신뢰도", style = MaterialTheme.typography.labelLarge, color = FeedLoopColors.Secondary)
        LinearProgressIndicator(progress = { currentCandidate.confidence.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = FeedLoopColors.Success, trackColor = FeedLoopColors.BorderMuted)
        Text("${(currentCandidate.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = FeedLoopColors.Secondary)
        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, FeedLoopColors.Border), enabled = !actionInFlight) { Text("취소") }
            Button(
                onClick = {
                    if (actionInFlight) return@Button
                    actionInFlight = true
                    actionError = null
                    scope.launch {
                        runCatching {
                            val candidateTitle = if (saveMode == CandidateSaveMode.Confirmed) title else currentCandidate.extractedTitle
                            repository.updateCandidate(candidateId, candidateTitle, startAt, endAt, location)
                            if (saveMode == CandidateSaveMode.Confirmed) {
                                repository.saveFromCandidate(
                                    candidateId = candidateId,
                                    selectedStatus = ScheduleStatus.Confirmed,
                                    titleOverride = title,
                                    recurrenceInput = recurrenceInput ?: return@launch,
                                )
                            } else {
                                repository.saveFromCandidate(
                                    candidateId = candidateId,
                                    selectedStatus = ScheduleStatus.Uncertain,
                                    titleOverride = null,
                                    recurrenceInput = recurrenceInput ?: return@launch,
                                    memoOverride = memo,
                                )
                            }
                        }.onSuccess { result ->
                            when (result) {
                                is SaveResult.Conflict -> onConflict()
                                is SaveResult.Saved -> onDone()
                                is SaveResult.SavedAsUncertain -> onDone()
                                else -> onDone()
                            }
                        }.onFailure { error ->
                            actionError = error.message?.takeIf { it.isNotBlank() }
                                ?: "저장에 실패했습니다. 네트워크와 로그인 상태를 확인해 주세요."
                            actionInFlight = false
                        }
                    }
                },
                modifier = Modifier.weight(1.2f),
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = if (saveMode == CandidateSaveMode.Confirmed) WarmAccent else ColdAccent),
            ) { Text(if (saveMode == CandidateSaveMode.Confirmed) "확정" else "미정 저장") }
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
private fun BasketTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accentColor: Color = FeedLoopColors.PrimaryDark,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = FeedLoopColors.Border,
            focusedLabelColor = accentColor,
            cursorColor = accentColor,
        ),
    )
}

@Composable
private fun SaveModeSelector(selectedMode: CandidateSaveMode, onModeChange: (CandidateSaveMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selectedMode == CandidateSaveMode.Uncertain,
            onClick = { onModeChange(CandidateSaveMode.Uncertain) },
            label = { Text("미정") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = ColdAccentLight,
                selectedLabelColor = ColdAccent,
                containerColor = FeedLoopColors.Surface,
            ),
        )
        FilterChip(
            selected = selectedMode == CandidateSaveMode.Confirmed,
            onClick = { onModeChange(CandidateSaveMode.Confirmed) },
            label = { Text("확정") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = WarmAccentLight,
                selectedLabelColor = WarmAccent,
                containerColor = FeedLoopColors.Surface,
            ),
        )
    }
}

private enum class CandidateSaveMode {
    Uncertain,
    Confirmed,
}

private fun AppointmentCandidateEntity?.defaultSaveMode(): CandidateSaveMode =
    if (this?.extractedStartAt != null) CandidateSaveMode.Confirmed else CandidateSaveMode.Uncertain

private fun parseSourceLabel(parseSource: String): String = when (parseSource) {
    AppointmentParseSourceValues.LlmSuccess,
    AppointmentParseSourceValues.LlmWithLocalSupplement -> "파싱 방식: LLM 파싱"
    AppointmentParseSourceValues.LocalFallback,
    AppointmentParseSourceValues.ParserError,
    AppointmentParseSourceValues.LocalOnly -> "파싱 방식: fallback"
    else -> "파싱 방식: 알 수 없음"
}

fun statusLabel(status: ScheduleStatus): String = when (status) {
    ScheduleStatus.Confirmed -> "확정"
    ScheduleStatus.Planned -> "예정"
    ScheduleStatus.Uncertain -> "보류"
}

private val ColdAccent = Color(0xFF2563EB)
private val ColdAccentLight = Color(0xFFE0F2FE)
private val WarmAccent = Color(0xFFD97706)
private val WarmAccentLight = Color(0xFFFFEDD5)
