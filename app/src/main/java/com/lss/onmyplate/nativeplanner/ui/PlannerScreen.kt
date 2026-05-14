package com.lss.onmyplate.nativeplanner.ui

import android.app.DatePickerDialog
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val basketZone = ZoneId.of("Asia/Seoul")
private val basketDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private const val BasketTag = "BasketScreen"

@Composable
fun BasketScreen(repository: PlannerRepository, onOpenCandidate: (String) -> Unit) {
    val pending by repository.observePendingCandidates().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var directInput by remember { mutableStateOf("") }
    var isCreatingCandidate by remember { mutableStateOf(false) }
    var confirmedExpanded by remember { mutableStateOf(false) }
    var savedFilter by remember { mutableStateOf(SavedScheduleFilter.Day) }
    val today = remember { LocalDate.now(basketZone) }
    var customStartDate by remember { mutableStateOf(today) }
    var customEndDate by remember { mutableStateOf(today) }
    val savedRange = remember(savedFilter, today, customStartDate, customEndDate) {
        savedScheduleRange(savedFilter, today, customStartDate, customEndDate)
    }
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
                Text("공유하거나 입력한 약속 후보를 정리해요. 확인할 후보 ${pending.size}개", style = MaterialTheme.typography.bodySmall, color = FeedLoopColors.Secondary)
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
                border = BorderStroke(1.dp, FeedLoopColors.Border),
                elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("약속 후보 만들기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = directInput,
                        onValueChange = { directInput = it },
                        label = { Text("약속 메시지 붙여넣기 또는 입력") },
                        placeholder = { Text("예: 다음 주 금요일 저녁 7시 강남에서 민수랑 저녁") },
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
                                try {
                                    val candidate = repository.createCandidate(rawText, "internal", System.currentTimeMillis())
                                    directInput = ""
                                    onOpenCandidate(candidate.id)
                                } catch (error: Throwable) {
                                    Log.e(BasketTag, "Failed to create appointment candidate from direct input. textLength=${rawText.length}", error)
                                    Toast.makeText(
                                        context,
                                        "Appointment parsing failed. Check login, network, and parser logs.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } finally {
                                    isCreatingCandidate = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = directInput.isNotBlank() && !isCreatingCandidate,
                        colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
                    ) { Text(if (isCreatingCandidate) "분석 중..." else "후보로 담기") }
                }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("정리할 약속 후보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (pending.isEmpty()) {
                    item { EmptyBasketCard() }
                }
                items(pending, key = { it.id }) { candidate ->
                    CandidateBasketCard(candidate = candidate, onClick = { onOpenCandidate(candidate.id) })
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

@Composable
fun CandidateBasketCard(candidate: AppointmentCandidateEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ready = candidate.extractedTitle.isNotBlank() && candidate.extractedStartAt != null
    val accent = if (ready) FeedLoopColors.Success else FeedLoopColors.Warning
    val statusText = if (ready) "확인 후 저장" else "정보 확인 필요"
    Card(
        modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
        border = BorderStroke(1.dp, if (ready) FeedLoopColors.SuccessBorder else FeedLoopColors.WarningBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(4.dp).height(92.dp).clip(CircleShape).background(accent))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        candidate.extractedTitle.ifBlank { "무슨 약속인지 확인 필요" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AssistChip(
                        onClick = onClick,
                        label = { Text(statusText) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (ready) FeedLoopColors.SuccessBg else FeedLoopColors.WarningBg,
                            labelColor = accent,
                        ),
                    )
                }
                Text(formatDateTime(candidate.extractedStartAt).ifBlank { "시간을 확인해 주세요" }, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Text(candidate.extractedLocation ?: "장소를 나중에 정해도 돼요", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("열어서 확인", color = FeedLoopColors.PrimaryDark, style = MaterialTheme.typography.labelMedium)
                    Text("저장 또는 보류", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun EmptyBasketCard() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("정리할 약속 후보가 없어요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("메시지를 입력하거나 Android 공유 기능으로 보내면 약속 후보로 담깁니다.", color = FeedLoopColors.Secondary)
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
                    Text("확정한 일정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("필요할 때 기간별로 저장된 일정을 확인해요.", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(onClick = onToggle, label = { Text("${schedules.size}개") })
            }

            if (expanded) {
                SavedScheduleFilterRow(filter = filter, onFilterChange = onFilterChange)
                if (filter == SavedScheduleFilter.Custom) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateOnlyField(
                            label = "시작일",
                            date = customStartDate,
                            onDateChange = onCustomStartDate,
                            modifier = Modifier.weight(1f),
                        )
                        DateOnlyField(
                            label = "종료일",
                            date = customEndDate,
                            onDateChange = onCustomEndDate,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (schedules.isEmpty()) {
                    Text("이 기간에 저장된 일정이 없어요.", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodyMedium)
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
            FilterChip(
                selected = filter == item,
                onClick = { onFilterChange(item) },
                label = { Text(item.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun DateOnlyField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
            if (occurrence.isRecurring) {
                AssistChip(onClick = {}, label = { Text("반복") })
            }
        }
        Text(formatDateTime(occurrence.occurrenceStartAt), color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
        schedule.location?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
