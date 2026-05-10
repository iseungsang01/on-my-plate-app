package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.repository.ScheduleOccurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val scheduleZone = ZoneId.of("Asia/Seoul")
private val dayLabelFormatter = DateTimeFormatter.ofPattern("M/d")
private val weekdayFormatter = DateTimeFormatter.ofPattern("E")
private const val timetableStartMinute = 8 * 60
private const val timetableEndMinute = 24 * 60
private const val defaultScheduleDurationMinutes = 60

@Composable
fun WeeklyScheduleScreen(repository: PlannerRepository, onOpenSchedule: (String, Long?) -> Unit) {
    val today = remember { LocalDate.now(scheduleZone) }
    var weekOffset by remember { mutableStateOf(0) }
    val weekStart = remember(today, weekOffset) {
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    }
    val days = remember(weekStart) { (0L..6L).map { weekStart.plusDays(it) } }
    val rangeStart = remember(days) { days.first().atStartOfDay(scheduleZone).toInstant().toEpochMilli() }
    val rangeEnd = remember(days) { days.last().plusDays(1).atStartOfDay(scheduleZone).toInstant().toEpochMilli() }
    val schedules by repository.observeExpandedSchedules(rangeStart, rangeEnd).collectAsState(initial = emptyList())
    val schedulesByDay = remember(schedules, days) {
        days.associateWith { day -> schedules.filter { it.localDate() == day } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeedLoopColors.Background, FeedLoopColors.PrimaryLight.copy(alpha = 0.45f))))
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            WeeklyTimetableWidget(
                days = days,
                schedulesByDay = schedulesByDay,
                onNextWeek = { weekOffset += 1 },
                onOpenSchedule = onOpenSchedule,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WeeklyTimetableWidget(
    days: List<LocalDate>,
    schedulesByDay: Map<LocalDate, List<ScheduleOccurrence>>,
    onNextWeek: () -> Unit,
    onOpenSchedule: (String, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
        border = BorderStroke(1.dp, FeedLoopColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${dayLabelFormatter.format(days.first())} - ${dayLabelFormatter.format(days.last())}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FeedLoopColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onNextWeek) {
                    Text(">", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            TimetableHeader(days = days)
            TimetableBody(
                days = days,
                schedulesByDay = schedulesByDay,
                onOpenSchedule = onOpenSchedule,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimetableHeader(days: List<LocalDate>) {
    val railWidth = 28.dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(FeedLoopColors.Tertiary),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(railWidth))
        days.forEach { day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(weekdayFormatter.format(day), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = FeedLoopColors.TextPrimary)
                Text(dayLabelFormatter.format(day), style = MaterialTheme.typography.labelSmall, color = FeedLoopColors.Secondary)
            }
        }
    }
}

@Composable
private fun TimetableBody(
    days: List<LocalDate>,
    schedulesByDay: Map<LocalDate, List<ScheduleOccurrence>>,
    onOpenSchedule: (String, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val railWidth = 28.dp
    val hourMarks = remember { (8..24 step 2).toList() }
    val eventsByDay = remember(schedulesByDay, days) {
        days.associateWith { day -> buildTimetableEvents(day, schedulesByDay[day].orEmpty()) }
    }
    val hasEvents = eventsByDay.values.any { it.isNotEmpty() }
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.82f))
            .border(1.dp, FeedLoopColors.BorderMuted, MaterialTheme.shapes.medium)
    ) {
        val bodyWidth = maxWidth
        val bodyHeight = maxHeight.coerceAtLeast(920.dp)
        val dayWidth = (bodyWidth - railWidth) / 7f

        Box(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            Box(Modifier.width(bodyWidth).height(bodyHeight)) {
                hourMarks.forEach { hour ->
                    val y = bodyHeight * ((hour * 60 - timetableStartMinute).toFloat() / (timetableEndMinute - timetableStartMinute).toFloat())
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
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = FeedLoopColors.TextMuted,
                    )
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
                            onOpenSchedule = onOpenSchedule,
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
    onOpenSchedule: (String, Long?) -> Unit,
) {
    val boundedStart = event.startMinute.coerceAtLeast(timetableStartMinute)
    val boundedEnd = event.endMinute.coerceAtMost(timetableEndMinute)
    if (boundedEnd <= timetableStartMinute || boundedStart >= timetableEndMinute) return

    val visibleMinutes = (timetableEndMinute - timetableStartMinute).toFloat()
    val laneWidth = dayWidth / event.laneCount.toFloat()
    val top = bodyHeight * ((boundedStart - timetableStartMinute).toFloat() / visibleMinutes) + 2.dp
    val height = (bodyHeight * ((boundedEnd - boundedStart).toFloat() / visibleMinutes) - 4.dp).coerceAtLeast(26.dp)
    val left = railWidth + (dayWidth * dayIndex.toFloat()) + (laneWidth * event.lane.toFloat()) + 3.dp
    val width = (laneWidth - 6.dp).coerceAtLeast(12.dp)

    Column(
        Modifier
            .offset(x = left, y = top)
            .width(width)
            .height(height)
            .clip(MaterialTheme.shapes.small)
            .background(FeedLoopColors.PrimaryLight)
            .border(1.dp, FeedLoopColors.Primary, MaterialTheme.shapes.small)
            .clickable { onOpenSchedule(event.occurrence.scheduleId, event.occurrence.occurrenceStartAt.takeIf { event.occurrence.isRecurring }) }
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            event.occurrence.schedule.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.Bold,
            color = FeedLoopColors.TextPrimary,
        )
        Text(
            if (event.occurrence.isRecurring) "${formatCompactRange(event.startMinute, event.endMinute)} · 반복" else formatCompactRange(event.startMinute, event.endMinute),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = FeedLoopColors.PrimaryDark,
        )
    }
}

private fun ScheduleOccurrence.localDate(): LocalDate = Instant.ofEpochMilli(schedule.startAt).atZone(scheduleZone).toLocalDate()

private data class TimetableEvent(
    val occurrence: ScheduleOccurrence,
    val startMinute: Int,
    val endMinute: Int,
    val lane: Int,
    val laneCount: Int,
)

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
        events.add(TimetableEvent(occurrence, startMinute, endMinute, lane, laneCount = 1))
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

private fun formatHourLabel(hour: Int): String = hour.toString()

private fun formatCompactRange(startMinute: Int, endMinute: Int): String =
    "${formatCompactMinute(startMinute)}-${formatCompactMinute(endMinute)}"

private fun formatCompactMinute(minute: Int): String {
    val bounded = minute.coerceIn(0, timetableEndMinute)
    val hour = (bounded / 60).toString()
    val minutePart = bounded % 60
    return if (minutePart == 0) hour else "$hour:${minutePart.toString().padStart(2, '0')}"
}

private fun labelOffset(y: Dp, bodyHeight: Dp): Dp = when {
    y <= 8.dp -> 2.dp
    y >= bodyHeight - 12.dp -> bodyHeight - 18.dp
    else -> y - 8.dp
}
