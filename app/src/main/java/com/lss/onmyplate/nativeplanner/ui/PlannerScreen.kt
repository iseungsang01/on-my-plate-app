package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import kotlinx.coroutines.launch

@Composable
fun BasketScreen(repository: PlannerRepository, onOpenCandidate: (String) -> Unit) {
    val pending by repository.observePendingCandidates().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var directInput by remember { mutableStateOf("") }
    var isCreatingCandidate by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("전체") }
    val filteredPending = remember(pending, selectedFilter) {
        when (selectedFilter) {
            "확정 가능" -> pending.filter { it.extractedTitle.isNotBlank() && it.extractedStartAt != null }
            "정보 부족" -> pending.filter { it.extractedTitle.isBlank() || it.extractedStartAt == null }
            else -> pending
        }
    }

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
                Text("약속 후보 바구니", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("메시지에서 찾은 약속 후보를 추가하고 확정해요. 현재 ${pending.size}개", style = MaterialTheme.typography.bodySmall, color = FeedLoopColors.Secondary)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("${pending.size}", "전체", FeedLoopColors.PrimaryDark, Modifier.weight(1f))
                StatPill("${pending.count { it.extractedStartAt != null }}", "시간 있음", FeedLoopColors.Success, Modifier.weight(1f))
                StatPill("${pending.count { it.extractedStartAt == null }}", "정보 부족", FeedLoopColors.Warning, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("전체", "확정 가능", "정보 부족").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FeedLoopColors.PrimaryLight,
                            selectedLabelColor = FeedLoopColors.PrimaryDark,
                            containerColor = FeedLoopColors.Surface,
                        ),
                    )
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
                border = BorderStroke(1.dp, FeedLoopColors.Border),
                elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("새 약속 후보 추가", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                                } finally {
                                    isCreatingCandidate = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = directInput.isNotBlank() && !isCreatingCandidate,
                        colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
                    ) { Text(if (isCreatingCandidate) "분석 중..." else "후보 만들기") }
                }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (filteredPending.isEmpty()) {
                    item { EmptyBasketCard(selectedFilter) }
                }
                items(filteredPending, key = { it.id }) { candidate ->
                    CandidateBasketCard(candidate = candidate, onClick = { onOpenCandidate(candidate.id) })
                }
            }
        }
    }
}

@Composable
private fun StatPill(count: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun CandidateBasketCard(candidate: AppointmentCandidateEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ready = candidate.extractedTitle.isNotBlank() && candidate.extractedStartAt != null
    val accent = if (ready) FeedLoopColors.Success else FeedLoopColors.Warning
    val statusText = if (ready) "확정 가능" else "수정 필요"
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
                    Text(candidate.extractedTitle.ifBlank { "제목 입력 필요" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AssistChip(onClick = onClick, label = { Text(statusText) }, colors = AssistChipDefaults.assistChipColors(containerColor = if (ready) FeedLoopColors.SuccessBg else FeedLoopColors.WarningBg, labelColor = accent))
                }
                Text(formatDateTime(candidate.extractedStartAt).ifBlank { "시간 정보 필요" }, color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Text(candidate.extractedLocation ?: "장소 정보 없음", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("수정", color = FeedLoopColors.PrimaryDark, style = MaterialTheme.typography.labelMedium)
                    Text("일정 추가", color = FeedLoopColors.Secondary, style = MaterialTheme.typography.labelMedium)
                    Text("버리기", color = FeedLoopColors.Error, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun EmptyBasketCard(filter: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${filter} 약속 후보가 없어요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("메시지를 입력하거나 Android 공유 기능으로 보내면 바구니에 후보가 담깁니다.", color = FeedLoopColors.Secondary)
        }
    }
}
