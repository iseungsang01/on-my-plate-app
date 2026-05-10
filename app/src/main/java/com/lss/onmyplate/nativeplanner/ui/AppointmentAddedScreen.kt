package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository

@Composable
fun AppointmentAddedScreen(
    repository: PlannerRepository,
    candidateId: String,
    onOpenPlanner: () -> Unit,
) {
    val candidate by repository.observeCandidate(candidateId).collectAsState(initial = null)

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF8FAFF), Color(0xFFFFFFFF))))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(FeedLoopColors.SuccessBg),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = FeedLoopColors.Success, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        }
        Text("일정에 추가했어요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("약속이 7일 일정 노트와 홈 위젯에 반영됩니다.", color = FeedLoopColors.Secondary)

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
            border = BorderStroke(1.dp, FeedLoopColors.Border),
            elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(candidate?.extractedTitle?.takeIf { it.isNotBlank() } ?: "추가된 약속", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(formatDateTime(candidate?.extractedStartAt).ifBlank { "시간 미정" }, color = FeedLoopColors.Secondary)
                Text(candidate?.extractedLocation ?: "장소 정보 없음", color = FeedLoopColors.Secondary)
            }
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onOpenPlanner, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
            Text("7일 일정 보기")
        }
        Button(onClick = onOpenPlanner, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark)) {
            Text("확인")
        }
    }
}
