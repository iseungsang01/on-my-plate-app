package com.lss.onmyplate.nativeplanner.ui

import android.app.DatePickerDialog
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.repository.ScheduleOccurrence
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val basketZone = ZoneId.of("Asia/Seoul")
private val basketDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private const val BasketTag = "BasketScreen"

@Composable
fun BasketScreen(repository: PlannerRepository, onOpenCandidate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var directInput by remember { mutableStateOf("") }
    var isCreatingCandidate by remember { mutableStateOf(false) }
    var saveScheduleError by remember { mutableStateOf<String?>(null) }
    var saveScheduleMessage by remember { mutableStateOf<String?>(null) }
    var confirmedExpanded by remember { mutableStateOf(false) }
    var savedFilter by remember { mutableStateOf(SavedScheduleFilter.Week) }
    val today = remember { LocalDate.now(basketZone) }
    var customStartDate by remember { mutableStateOf(today) }
    var customEndDate by remember { mutableStateOf(today) }
    val savedRange = remember(savedFilter, today, customStartDate, customEndDate) {
        savedScheduleRange(savedFilter, today, customStartDate, customEndDate)
    }
    val pendingCandidates by repository.observePendingCandidates().collectAsState(initial = emptyList())
    val savedSchedules by repository.observeExpandedSchedules(savedRange.first, savedRange.second).collectAsState(initial = emptyList())

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFFFEFD7))))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("약속 바구니", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("공유하거나 입력한 약속을 일정 디테일로 설정한 뒤 확정하면 시간표에 들어갑니다.", style = MaterialTheme.typography.bodySmall, color = FeedLoopColors.Secondary)
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
                border = BorderStroke(1.dp, FeedLoopColors.Border),
                elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("일정 디테일 설정 만들기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = directInput,
                        onValueChange = {
                            directInput = it
                            saveScheduleError = null
                            saveScheduleMessage = null
                        },
                        label = { Text("약속 메시지 붙여넣기 또는 입력") },
                        placeholder = { Text("예: 다음 주 금요일 저녁 7시 강남에서 민수와 약속") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
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
                                saveScheduleError = null
                                saveScheduleMessage = null
                                try {
                                    val candidate = repository.createCandidate(rawText, "internal", System.currentTimeMillis())
                                    directInput = ""
                                    saveScheduleMessage = "일정 디테일 설정을 만들었습니다."
                                    onOpenCandidate(candidate.id)
                                } catch (error: Throwable) {
                                    Log.e(BasketTag, "Failed to create appointment detail setup from direct input. textLength=${rawText.length}", error)
                                    val message = saveScheduleErrorMessage(error)
                                    saveScheduleError = message
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                } finally {
                                    isCreatingCandidate = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = directInput.isNotBlank() && !isCreatingCandidate,
                        colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
                    ) { Text(if (isCreatingCandidate) "설정 만드는 중..." else "디테일 설정 열기") }
                    saveScheduleMessage?.let { message ->
                        Text(text = message, color = FeedLoopColors.Success, style = MaterialTheme.typography.bodySmall)
                    }
                    saveScheduleError?.let { message ->
                        Text(text = "저장 실패: $message", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    CandidateSetupSection(
                        candidates = pendingCandidates,
                        onOpenCandidate = onOpenCandidate,
                    )
                }
                item {
                    SavedScheduleSection(
                        expanded = confirmedExpanded,
                        onToggle = { confirmedExpanded = !confirmedExpanded },
                        filter = savedFilter,
                        onFilterChange = { savedFilter = it },
                        customStartDate = customStartDate,
                        onCustomStartDate = {
                            customStartDate = it
                            if (customEndDate.isBefore(it)) customEndDate = it
                        },
                        customEndDate = customEndDate,
                        onCustomEndDate = {
                            customEndDate = it
                            if (customStartDate.isAfter(it)) customStartDate = it
                        },
                        schedules = savedSchedules,
                    )
                }
            }
        }
    }
}

private fun saveScheduleErrorMessage(error: Throwable): String {
    val detail = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
    return when {
        detail == "Login is required." -> "로그인이 필요합니다. 설정/로그인 화면에서 다시 로그인해 주세요."
        detail == "Planner API is not configured." -> "Planner API 주소가 앱에 설정되지 않았습니다."
        error is IOException || error.cause is IOException -> "네트워크 요청 실패: ${detail ?: error::class.java.simpleName}"
        detail != null -> detail
        else -> error::class.java.simpleName
    }
}

@Composable
private fun CandidateSetupSection(
    candidates: List<AppointmentCandidateEntity>,
    onOpenCandidate: (String) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
        border = BorderStroke(1.dp, FeedLoopColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("일정 디테일 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("확정 전 제목, 시간, 장소를 확인하는 임시 설정입니다.", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(onClick = {}, label = { Text("${candidates.size}개") })
            }
            if (candidates.isEmpty()) {
                Text("설정할 일정이 없습니다.", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.sortedByDescending { it.createdAt }.forEach { candidate ->
                        CandidateSetupRow(candidate = candidate, onOpen = { onOpenCandidate(candidate.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateSetupRow(candidate: AppointmentCandidateEntity, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(FeedLoopColors.PendingBg.copy(alpha = 0.75f))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                candidate.extractedTitle.takeIf { it.isNotBlank() } ?: "제목 입력 필요",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AssistChip(onClick = onOpen, label = { Text("설정") })
        }
        Text(formatDateTime(candidate.extractedStartAt).ifBlank { "시간 미정" }, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
        candidate.extractedLocation?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SavedScheduleSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    filter: SavedScheduleFilter,
    onFilterChange: (SavedScheduleFilter) -> Unit,
    customStartDate: LocalDate,
    onCustomStartDate: (LocalDate) -> Unit,
    customEndDate: LocalDate,
    onCustomEndDate: (LocalDate) -> Unit,
    schedules: List<ScheduleOccurrence>,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
        border = BorderStroke(1.dp, FeedLoopColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "v" else ">", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text("시간표에 들어간 일정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("확정 후 저장된 최종 일정을 기간별로 확인합니다.", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(onClick = onToggle, label = { Text("${schedules.size}개") })
            }

            if (expanded) {
                SavedScheduleFilterRow(filter = filter, onFilterChange = onFilterChange)
                if (filter == SavedScheduleFilter.Custom) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateOnlyField(label = "시작일", date = customStartDate, onDateChange = onCustomStartDate, modifier = Modifier.weight(1f))
                        DateOnlyField(label = "종료일", date = customEndDate, onDateChange = onCustomEndDate, modifier = Modifier.weight(1f))
                    }
                }
                if (schedules.isEmpty()) {
                    Text("이 기간에 시간표에 들어간 일정이 없습니다.", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        schedules.sortedBy { it.occurrenceStartAt }.forEach { occurrence ->
                            SavedScheduleRow(occurrence)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedScheduleFilterRow(filter: SavedScheduleFilter, onFilterChange: (SavedScheduleFilter) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SavedScheduleFilter.entries.forEach { item ->
            FilterChip(selected = filter == item, onClick = { onFilterChange(item) }, label = { Text(item.label, maxLines = 1) })
        }
    }
}

@Composable
private fun DateOnlyField(label: String, date: LocalDate, onDateChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun showDatePicker() {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth -> onDateChange(LocalDate.of(year, month + 1, dayOfMonth)) },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth,
        ).show()
    }

    Box(modifier) {
        OutlinedTextField(
            value = basketDateFormatter.format(date),
            onValueChange = {},
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = FeedLoopColors.PrimaryDark,
                unfocusedBorderColor = FeedLoopColors.Border,
                focusedLabelColor = FeedLoopColors.PrimaryDark,
                cursorColor = FeedLoopColors.PrimaryDark,
            ),
        )
        Box(Modifier.matchParentSize().clickable { showDatePicker() })
    }
}

@Composable
private fun SavedScheduleRow(occurrence: ScheduleOccurrence) {
    val schedule = occurrence.schedule
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(FeedLoopColors.Tertiary.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                schedule.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (occurrence.isRecurring) AssistChip(onClick = {}, label = { Text("반복") })
        }
        val metadata = listOfNotNull(
            formatDateTime(occurrence.occurrenceStartAt).takeIf { it.isNotBlank() },
            schedule.location?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        Text(metadata, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class SavedScheduleFilter(val label: String) {
    Day("하루"),
    Week("7일"),
    Month("한 달"),
    Custom("기간"),
}

private fun savedScheduleRange(
    filter: SavedScheduleFilter,
    today: LocalDate,
    customStartDate: LocalDate,
    customEndDate: LocalDate,
): Pair<Long, Long> {
    val startDate = when (filter) {
        SavedScheduleFilter.Custom -> minOf(customStartDate, customEndDate)
        else -> today
    }
    val endExclusiveDate = when (filter) {
        SavedScheduleFilter.Day -> today.plusDays(1)
        SavedScheduleFilter.Week -> today.plusDays(7)
        SavedScheduleFilter.Month -> today.plusMonths(1)
        SavedScheduleFilter.Custom -> maxOf(customStartDate, customEndDate).plusDays(1)
    }
    return startDate.atStartOfDay(basketZone).toInstant().toEpochMilli() to
        endExclusiveDate.atStartOfDay(basketZone).toInstant().toEpochMilli()
}
