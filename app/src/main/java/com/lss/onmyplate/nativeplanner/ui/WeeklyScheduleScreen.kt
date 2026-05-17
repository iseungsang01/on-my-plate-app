package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.repository.ScheduleOccurrence
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

private val scheduleZone = ZoneId.of("Asia/Seoul")
private val dayLabelFormatter = DateTimeFormatter.ofPattern("M/d")
private val weekdayFormatter = DateTimeFormatter.ofPattern("E")
private val timetableRailWidth = 24.dp
private const val timetableEndMinute = 24 * 60
private const val defaultScheduleDurationMinutes = 60

@Composable
fun WeeklyScheduleScreen(repository: PlannerRepository, onOpenSchedule: (String, Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now(scheduleZone) }
    var weekOffset by remember { mutableStateOf(0) }
    val weekStart = remember(today, weekOffset) {
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    }
    val days = remember(weekStart) { (0L..6L).map { weekStart.plusDays(it) } }
    val rangeStart = remember(days) { days.first().atStartOfDay(scheduleZone).toInstant().toEpochMilli() }
    val rangeEnd = remember(days) { days.last().plusDays(1).atStartOfDay(scheduleZone).toInstant().toEpochMilli() }
    val schedules by repository.observeExpandedSchedules(rangeStart, rangeEnd).collectAsState(initial = emptyList())
    val runtimeState by repository.runtimeState.collectAsState()
    val schedulesByDay = remember(schedules, days) {
        days.associateWith { day -> schedules.filter { it.localDate() == day } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeedLoopColors.Background, FeedLoopColors.PrimaryLight.copy(alpha = 0.45f))))
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            runtimeState.errorMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = FeedLoopColors.ErrorBg),
                    border = BorderStroke(1.dp, FeedLoopColors.Error.copy(alpha = 0.35f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "일정 동기화 실패: $message",
                            color = FeedLoopColors.Error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { repository.clearRuntimeError() }) { Text("닫기") }
                    }
                }
            }
            if (runtimeState.loading && schedules.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            WeeklyTimetableWidget(
                days = days,
                schedulesByDay = schedulesByDay,
                onPreviousWeek = { weekOffset -= 1 },
                onNextWeek = { weekOffset += 1 },
                onOpenSchedule = onOpenSchedule,
                onMoveSchedule = { occurrence, newStartAt ->
                    if (occurrence.isRecurring) {
                        onOpenSchedule(occurrence.scheduleId, occurrence.occurrenceStartAt)
                    } else {
                        scope.launch {
                            val schedule = occurrence.schedule
                            val duration = schedule.endAt?.minus(schedule.startAt)?.coerceAtLeast(30 * 60_000L)
                            runCatching {
                                repository.updateSchedule(
                                    scheduleId = occurrence.scheduleId,
                                    title = schedule.title,
                                    startAt = newStartAt,
                                    endAt = duration?.let { newStartAt + it },
                                    location = schedule.location,
                                    memo = schedule.memo,
                                    status = scheduleStatusFromDb(schedule.status),
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WeeklyTimetableWidget(
    days: List<LocalDate>,
    schedulesByDay: Map<LocalDate, List<ScheduleOccurrence>>,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onOpenSchedule: (String, Long?) -> Unit,
    onMoveSchedule: (ScheduleOccurrence, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visibleStartHour by remember { mutableStateOf(8) }
    var visibleEndHour by remember { mutableStateOf(24) }
    var showTimeRangeSettings by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var showSnapGrid by remember { mutableStateOf(false) }

    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
        border = BorderStroke(1.dp, FeedLoopColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onPreviousWeek, modifier = Modifier.size(32.dp)) {
                    Text("<", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    "${dayLabelFormatter.format(days.first())} - ${dayLabelFormatter.format(days.last())}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = FeedLoopColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showTimeRangeSettings = true }, modifier = Modifier.size(32.dp)) {
                    Text("⚙", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = {
                        editMode = !editMode
                        if (!editMode) showSnapGrid = false
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (editMode) FeedLoopColors.PrimaryLight else Color.Transparent),
                ) {
                    Text("✎", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (editMode) FeedLoopColors.PrimaryDark else FeedLoopColors.TextPrimary)
                }
                IconButton(onClick = onNextWeek, modifier = Modifier.size(32.dp)) {
                    Text(">", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
            TimetableHeader(days = days)
            TimetableBody(
                days = days,
                schedulesByDay = schedulesByDay,
                startHour = visibleStartHour,
                endHour = visibleEndHour,
                editMode = editMode,
                showSnapGrid = showSnapGrid,
                onOpenSchedule = onOpenSchedule,
                onMoveSchedule = onMoveSchedule,
                onDragStateChange = { showSnapGrid = it },
                modifier = Modifier.weight(1f),
            )
            if (editMode) {
                Text("✎ 편집 모드: 일정을 길게 눌러 30분 격자에 맞춰 드래그하세요.", color = FeedLoopColors.PrimaryDark, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showTimeRangeSettings) {
        TimeRangeSettingsDialog(
            initialStartHour = visibleStartHour,
            initialEndHour = visibleEndHour,
            onDismiss = { showTimeRangeSettings = false },
            onSave = { start, end ->
                visibleStartHour = start
                visibleEndHour = end
                showTimeRangeSettings = false
            },
        )
    }
}

@Composable
private fun TimeRangeSettingsDialog(
    initialStartHour: Int,
    initialEndHour: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var startHourInput by remember(initialStartHour) { mutableStateOf(initialStartHour.toString()) }
    var endHourInput by remember(initialEndHour) { mutableStateOf(initialEndHour.toString()) }
    val parsedStartHour = startHourInput.toIntOrNull()
    val parsedEndHour = endHourInput.toIntOrNull()
    val canSave = parsedStartHour != null &&
        parsedEndHour != null &&
        parsedStartHour in 0..23 &&
        parsedEndHour in 1..24 &&
        parsedStartHour < parsedEndHour

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시간표 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("시간표에 표시할 시간 범위를 바꿉니다.", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = startHourInput,
                        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) startHourInput = it },
                        label = { Text("시작") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text("~", color = FeedLoopColors.Secondary, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = endHourInput,
                        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) endHourInput = it },
                        label = { Text("끝") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                if (!canSave) {
                    Text("시작은 0~23, 끝은 1~24 사이에서 시작보다 크게 입력해 주세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val start = parsedStartHour ?: return@TextButton
                    val end = parsedEndHour ?: return@TextButton
                    if (start in 0..23 && end in 1..24 && start < end) onSave(start, end)
                },
                enabled = canSave,
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}

@Composable
private fun TimetableHeader(days: List<LocalDate>) {
    val railWidth = timetableRailWidth
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(FeedLoopColors.Tertiary),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(railWidth))
        days.forEach { day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(weekdayFormatter.format(day), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), fontWeight = FontWeight.Bold, color = FeedLoopColors.TextPrimary)
                Text(dayLabelFormatter.format(day), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = FeedLoopColors.Secondary)
            }
        }
    }
}

@Composable
private fun TimetableBody(
    days: List<LocalDate>,
    schedulesByDay: Map<LocalDate, List<ScheduleOccurrence>>,
    startHour: Int,
    endHour: Int,
    editMode: Boolean,
    showSnapGrid: Boolean,
    onOpenSchedule: (String, Long?) -> Unit,
    onMoveSchedule: (ScheduleOccurrence, Long) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val railWidth = timetableRailWidth
    val startMinute = startHour * 60
    val endMinute = endHour * 60
    val hourMarks = remember(startHour, endHour) { (startHour..endHour step 2).toList() }
    val eventsByDay = remember(schedulesByDay, days) {
        days.associateWith { day -> buildTimetableEvents(day, schedulesByDay[day].orEmpty()) }
    }
    val hasEvents = eventsByDay.values.any { it.isNotEmpty() }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.82f))
            .border(1.dp, FeedLoopColors.BorderMuted, MaterialTheme.shapes.medium)
    ) {
        val bodyWidth = maxWidth
        val bodyHeight = maxHeight
        val dayWidth = (bodyWidth - railWidth) / 7f

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.width(bodyWidth).height(bodyHeight)) {
                hourMarks.forEach { hour ->
                    val y = bodyHeight * ((hour * 60 - startMinute).toFloat() / (endMinute - startMinute).toFloat())
                    Box(
                        Modifier
                            .offset(x = railWidth, y = y)
                            .width(bodyWidth - railWidth)
                            .height(1.dp)
                            .background(if (hour % 4 == 0) FeedLoopColors.Border else FeedLoopColors.BorderMuted)
                    )
                    Text(
                        formatHourLabel(hour),
                        modifier = Modifier
                            .width(railWidth)
                            .offset(y = labelOffset(y, bodyHeight)),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = FeedLoopColors.TextMuted,
                    )
                }

                if (editMode && showSnapGrid) {
                    val snapMarks = ((startMinute + 29) / 30..endMinute / 30).map { it * 30 }
                    snapMarks.forEach { minute ->
                        val y = bodyHeight * ((minute - startMinute).toFloat() / (endMinute - startMinute).toFloat())
                        Box(
                            Modifier
                                .offset(x = railWidth, y = y)
                                .width(bodyWidth - railWidth)
                                .height(1.dp)
                                .background(if (minute % 60 == 0) FeedLoopColors.Primary.copy(alpha = 0.45f) else FeedLoopColors.Primary.copy(alpha = 0.22f))
                        )
                    }
                }

                (0..7).forEach { index ->
                    Box(
                        Modifier
                            .offset(x = railWidth + (dayWidth * index.toFloat()))
                            .width(1.dp)
                            .height(bodyHeight)
                            .background(FeedLoopColors.BorderMuted)
                    )
                }

                if (!hasEvents) {
                    Box(Modifier.fillMaxSize().padding(start = railWidth), contentAlignment = Alignment.Center) {
                        Text("일정 없음", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                days.forEachIndexed { dayIndex, day ->
                    eventsByDay[day].orEmpty().forEach { event ->
                        TimetableEventBlock(
                            event = event,
                            dayIndex = dayIndex,
                            dayWidth = dayWidth,
                            railWidth = railWidth,
                            bodyHeight = bodyHeight,
                            visibleRangeStartMinute = startMinute,
                            visibleRangeEndMinute = endMinute,
                            days = days,
                            editMode = editMode,
                            onOpenSchedule = onOpenSchedule,
                            onMoveSchedule = onMoveSchedule,
                            onDragStateChange = onDragStateChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableEventBlock(
    event: TimetableEvent,
    dayIndex: Int,
    dayWidth: Dp,
    railWidth: Dp,
    bodyHeight: Dp,
    visibleRangeStartMinute: Int,
    visibleRangeEndMinute: Int,
    days: List<LocalDate>,
    editMode: Boolean,
    onOpenSchedule: (String, Long?) -> Unit,
    onMoveSchedule: (ScheduleOccurrence, Long) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
) {
    val visibleStartMinute = event.startMinute
    val visibleEndMinute = event.endMinute
    if (visibleEndMinute <= visibleStartMinute) return

    val visibleMinutes = (visibleRangeEndMinute - visibleRangeStartMinute).toFloat().coerceAtLeast(1f)
    val laneWidth = dayWidth / event.laneCount.toFloat()
    val top = bodyHeight * ((visibleStartMinute - visibleRangeStartMinute).toFloat() / visibleMinutes) + 1.dp
    val height = (bodyHeight * ((visibleEndMinute - visibleStartMinute).toFloat() / visibleMinutes) - 2.dp).coerceAtLeast(24.dp)
    val left = railWidth + (dayWidth * dayIndex.toFloat()) + (laneWidth * event.lane.toFloat()) + 2.dp
    val width = (laneWidth - 4.dp).coerceAtLeast(12.dp)
    val showTime = height >= 30.dp && width >= 26.dp
    val showLocation = height >= 46.dp && width >= 34.dp && !event.occurrence.schedule.location.isNullOrBlank()
    val density = LocalDensity.current
    val dayWidthPx = with(density) { dayWidth.toPx() }.coerceAtLeast(1f)
    val bodyHeightPx = with(density) { bodyHeight.toPx() }.coerceAtLeast(1f)
    val visibleDragMinutes = (visibleRangeEndMinute - visibleRangeStartMinute).coerceAtLeast(1)
    var dragOffset by remember(event.occurrence.scheduleId, event.occurrence.occurrenceStartAt) { mutableStateOf(Offset.Zero) }
    val dragModifier = if (editMode) {
        Modifier.pointerInput(event.occurrence.scheduleId, event.occurrence.occurrenceStartAt, dayIndex, dayWidthPx, bodyHeightPx, visibleRangeStartMinute, visibleRangeEndMinute) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    dragOffset = Offset.Zero
                    onDragStateChange(true)
                },
                onDragCancel = {
                    dragOffset = Offset.Zero
                    onDragStateChange(false)
                },
                onDragEnd = {
                    val newDayIndex = (dayIndex + (dragOffset.x / dayWidthPx).roundToInt()).coerceIn(days.indices)
                    val durationMinutes = event.durationMinutes.coerceAtLeast(30)
                    val rawStartMinute = event.startMinute + ((dragOffset.y / bodyHeightPx) * visibleDragMinutes).roundToInt()
                    val snappedStartMinute = snapToThirtyMinutes(rawStartMinute)
                        .coerceIn(visibleRangeStartMinute, (visibleRangeEndMinute - durationMinutes).coerceAtLeast(visibleRangeStartMinute))
                    val newStartAt = days[newDayIndex]
                        .atStartOfDay(scheduleZone)
                        .plusMinutes(snappedStartMinute.toLong())
                        .toInstant()
                        .toEpochMilli()
                    dragOffset = Offset.Zero
                    onDragStateChange(false)
                    if (newStartAt != event.occurrence.schedule.startAt) {
                        onMoveSchedule(event.occurrence, newStartAt)
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragOffset += dragAmount
                },
            )
        }
    } else {
        Modifier.clickable { onOpenSchedule(event.occurrence.scheduleId, event.occurrence.occurrenceStartAt.takeIf { event.occurrence.isRecurring }) }
    }

    Column(
        Modifier
            .offset(x = left + with(density) { dragOffset.x.toDp() }, y = top + with(density) { dragOffset.y.toDp() })
            .width(width)
            .height(height)
            .clip(MaterialTheme.shapes.small)
            .background(if (editMode) FeedLoopColors.PrimaryLight.copy(alpha = 0.88f) else FeedLoopColors.PrimaryLight)
            .border(if (editMode) 2.dp else 1.dp, FeedLoopColors.Primary, MaterialTheme.shapes.small)
            .then(dragModifier)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            event.occurrence.schedule.title,
            maxLines = if (showTime) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = FontWeight.Bold,
            color = FeedLoopColors.TextPrimary,
        )
        if (showTime) {
            Text(
                if (event.occurrence.isRecurring) "${formatCompactRange(event.startMinute, event.endMinute)} · 반복" else formatCompactRange(event.startMinute, event.endMinute),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = FeedLoopColors.PrimaryDark,
            )
        }
        if (showLocation) {
            Text(
                event.occurrence.schedule.location.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = FeedLoopColors.Secondary,
            )
        }
    }
}

private fun ScheduleOccurrence.localDate(): LocalDate = Instant.ofEpochMilli(schedule.startAt).atZone(scheduleZone).toLocalDate()

private data class TimetableEvent(
    val occurrence: ScheduleOccurrence,
    val startMinute: Int,
    val endMinute: Int,
    val lane: Int,
    val laneCount: Int,
) {
    val durationMinutes: Int = (endMinute - startMinute).coerceAtLeast(30)
}

private fun buildTimetableEvents(day: LocalDate, schedules: List<ScheduleOccurrence>): List<TimetableEvent> {
    val laneEnds = mutableListOf<Int>()
    val events = mutableListOf<TimetableEvent>()
    var laneCount = 0

    schedules.sortedWith(compareBy<ScheduleOccurrence> { it.schedule.startAt }.thenBy { it.schedule.title }).forEach { occurrence ->
        val schedule = occurrence.schedule
        val startMinute = schedule.minuteOfDay()
        val endMinute = schedule.endMinuteOfDay(day).coerceAtLeast(startMinute + 30)
        var lane = 0
        while (lane < laneEnds.size && laneEnds[lane] > startMinute) {
            lane += 1
        }
        if (lane == laneEnds.size) {
            laneEnds.add(endMinute)
        } else {
            laneEnds[lane] = endMinute
        }
        laneCount = maxOf(laneCount, lane + 1)
        events.add(
            TimetableEvent(
                occurrence = occurrence,
                startMinute = startMinute,
                endMinute = endMinute,
                lane = lane,
                laneCount = 1,
            ),
        )
    }

    return events.map { it.copy(laneCount = laneCount.coerceAtLeast(1)) }
}

private fun com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity.minuteOfDay(): Int {
    val time = Instant.ofEpochMilli(startAt).atZone(scheduleZone).toLocalTime()
    return time.hour * 60 + time.minute
}

private fun com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity.endMinuteOfDay(day: LocalDate): Int {
    val fallbackEnd = startAt + defaultScheduleDurationMinutes * 60_000L
    val endDateTime = Instant.ofEpochMilli(endAt ?: fallbackEnd).atZone(scheduleZone)
    if (endDateTime.toLocalDate().isAfter(day)) return timetableEndMinute
    return endDateTime.toLocalTime().let { it.hour * 60 + it.minute }
}

private fun formatHourLabel(hour: Int): String = hour.toString().padStart(2, '0')

private fun formatCompactRange(startMinute: Int, endMinute: Int): String =
    "${formatCompactMinute(startMinute)}-${formatCompactMinute(endMinute)}"

private fun formatCompactMinute(minute: Int): String {
    val bounded = minute.coerceIn(0, timetableEndMinute)
    val hour = (bounded / 60).toString()
    val minutePart = bounded % 60
    return if (minutePart == 0) hour else "$hour:${minutePart.toString().padStart(2, '0')}"
}

private fun snapToThirtyMinutes(minute: Int): Int = (minute / 30f).roundToInt() * 30

private fun labelOffset(y: Dp, bodyHeight: Dp): Dp = when {
    y <= 8.dp -> 2.dp
    y >= bodyHeight - 12.dp -> bodyHeight - 18.dp
    else -> y - 8.dp
}
