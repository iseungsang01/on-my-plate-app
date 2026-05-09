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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.supabase.ShareGroup
import com.lss.onmyplate.nativeplanner.data.supabase.SharedSchedule
import com.lss.onmyplate.nativeplanner.data.supabase.SharingRepository
import kotlinx.coroutines.launch

@Composable
fun SharingScreen(
    plannerRepository: PlannerRepository,
    sharingRepository: SharingRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var publicId by remember { mutableStateOf(sharingRepository.cachedPublicId().orEmpty()) }
    var partnerId by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<ShareGroup>>(emptyList()) }
    var selectedGroup by remember { mutableStateOf<ShareGroup?>(null) }
    var localSchedules by remember { mutableStateOf<List<ScheduleEntity>>(emptyList()) }
    var sharedSchedules by remember { mutableStateOf<List<SharedSchedule>>(emptyList()) }
    var includeDummy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            message = null
            try {
                if (!sharingRepository.isConfigured()) {
                    message = "공유 API 설정이 없습니다. .env에 PLANNER_API_BASE_URL을 넣어주세요."
                    localSchedules = plannerRepository.getSchedules()
                    return@launch
                }
                publicId = sharingRepository.ensureProfile().publicId
                groups = sharingRepository.listGroups()
                selectedGroup = selectedGroup?.let { selected -> groups.firstOrNull { it.id == selected.id } } ?: groups.firstOrNull()
                localSchedules = plannerRepository.getSchedules()
                selectedGroup?.let { sharedSchedules = sharingRepository.listSharedSchedules(it.id, includeDummy) }
            } catch (t: Throwable) {
                message = t.message ?: "공유 정보를 불러오지 못했습니다."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(selectedGroup?.id, includeDummy) {
        selectedGroup?.let { group ->
            runCatching { sharingRepository.listSharedSchedules(group.id, includeDummy) }
                .onSuccess { sharedSchedules = it }
                .onFailure { message = it.message }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(FeedLoopColors.Background, FeedLoopColors.PrimaryLight.copy(alpha = 0.35f))),
        ),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("← 일정") }
                    Text("공유 일정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Card(colors = FeedLoopCardColors(), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("내 공유 ID", fontWeight = FontWeight.SemiBold)
                        Text(if (publicId.isBlank()) "생성 전" else publicId, style = MaterialTheme.typography.titleLarge, color = FeedLoopColors.PrimaryDark)
                        Text("상대가 이 ID를 입력하면 같은 공유 그룹에서 일정을 볼 수 있습니다.", color = FeedLoopColors.Secondary)
                        OutlinedTextField(
                            value = partnerId,
                            onValueChange = { partnerId = it.trim() },
                            label = { Text("상대 공유 ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    loading = true
                                    message = null
                                    try {
                                        val group = sharingRepository.createGroupWithPartner(partnerId)
                                        partnerId = ""
                                        groups = sharingRepository.listGroups()
                                        selectedGroup = group
                                        sharedSchedules = sharingRepository.listSharedSchedules(group.id, includeDummy)
                                        message = "공유 그룹을 만들었습니다."
                                    } catch (t: Throwable) {
                                        message = t.message ?: "공유 그룹 생성 실패"
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                            enabled = partnerId.isNotBlank() && !loading && sharingRepository.isConfigured(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("상대 ID로 공유 시작") }
                    }
                }
            }
            message?.let { text ->
                item {
                    Text(text, color = if (text.contains("실패") || text.contains("없습니다")) FeedLoopColors.Error else FeedLoopColors.Secondary)
                }
            }
            if (groups.isNotEmpty()) {
                item { Text("공유 그룹", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        groups.forEach { group ->
                            FilterChip(
                                selected = selectedGroup?.id == group.id,
                                onClick = { selectedGroup = group },
                                label = { Text(group.name) },
                            )
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeDummy, onCheckedChange = { includeDummy = it })
                    Text("공유 화면 전용 더미 일정 함께 보기")
                }
            }
            item { Text("내 로컬 일정 공유하기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (localSchedules.isEmpty()) item { Text("공유할 로컬 일정이 없습니다.", color = FeedLoopColors.Secondary) }
            items(localSchedules, key = { it.id }) { schedule ->
                LocalShareRow(schedule = schedule, enabled = selectedGroup != null && !loading && sharingRepository.isConfigured()) {
                    scope.launch {
                        val group = selectedGroup ?: return@launch
                        loading = true
                        message = null
                        try {
                            sharingRepository.uploadSchedule(group.id, schedule)
                            sharedSchedules = sharingRepository.listSharedSchedules(group.id, includeDummy)
                            message = "'${schedule.title}' 일정을 공유했습니다."
                        } catch (t: Throwable) {
                            message = t.message ?: "일정 공유 실패"
                        } finally {
                            loading = false
                        }
                    }
                }
            }
            item { Text("공유 그룹 일정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (sharedSchedules.isEmpty()) item { Text("아직 공유된 일정이 없습니다.", color = FeedLoopColors.Secondary) }
            items(sharedSchedules, key = { it.id + it.isDummy }) { schedule -> SharedScheduleRow(schedule) }
        }
    }
}

@Composable
private fun LocalShareRow(schedule: ScheduleEntity, enabled: Boolean, onUpload: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = FeedLoopCardColors(), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(schedule.title, fontWeight = FontWeight.SemiBold)
                Text("${formatDateTime(schedule.startAt)} · ${schedule.status}", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onUpload, enabled = enabled) { Text("공유") }
        }
    }
}

@Composable
private fun SharedScheduleRow(schedule: SharedSchedule) {
    Card(Modifier.fillMaxWidth(), colors = FeedLoopCardColors(), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(schedule.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (schedule.isDummy) AssistChip(onClick = {}, label = { Text("공유 전용") })
            }
            Text("${formatDateTime(schedule.startAt)} · ${schedule.status}", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
            schedule.location?.let { Text(it, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

