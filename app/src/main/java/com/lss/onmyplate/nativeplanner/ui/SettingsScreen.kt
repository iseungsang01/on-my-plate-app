package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.auth.AuthRepository
import com.lss.onmyplate.nativeplanner.data.supabase.SharingRepository

@Composable
fun SettingsScreen(
    authRepository: AuthRepository,
    sharingRepository: SharingRepository,
    onLoggedOut: () -> Unit = {},
) {
    var sessionPresent by remember { mutableStateOf(sharingRepository.hasCachedSession()) }
    var publicId by remember { mutableStateOf(sharingRepository.cachedPublicId().orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }

    val loginState = when {
        authRepository.isGuestMode() -> "게스트 모드"
        sessionPresent -> "세션 있음"
        else -> "로그인 필요"
    }

    Column(
        modifier = Modifier
            .background(
                Brush.verticalGradient(
                    listOf(FeedLoopColors.Background, FeedLoopColors.PendingBg.copy(alpha = 0.35f)),
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("설정과 계정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("로그인, 게스트 모드, 앱 정보를 관리합니다.", color = FeedLoopColors.Secondary)

        SettingsCard(title = "계정") {
            SettingLine("로그인 상태", loginState)
            SettingLine("내 공유 ID", publicId.ifBlank { "생성 전" })
            SettingLine("공유 API", if (sharingRepository.isConfigured()) "설정됨" else "미설정")
            OutlinedButton(
                onClick = {
                    authRepository.clearAccess()
                    sharingRepository.clearAccountCache()
                    sessionPresent = false
                    publicId = ""
                    message = "저장된 세션과 게스트 상태를 삭제했습니다."
                    onLoggedOut()
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, FeedLoopColors.Border),
            ) {
                Text("로그아웃")
            }
        }

        SettingsCard(title = "비밀번호") {
            Text("현재 Android 앱에는 비밀번호 변경 API가 연결되어 있지 않습니다.", color = FeedLoopColors.Secondary)
            Text(
                "계정 API가 추가되면 이 화면에서 현재 비밀번호 확인 후 새 비밀번호로 변경하도록 연결합니다.",
                color = FeedLoopColors.Secondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { message = "비밀번호 변경은 계정 API 연동 후 제공됩니다." }, modifier = Modifier.fillMaxWidth(), enabled = true) {
                Text("비밀번호 변경 준비")
            }
        }

        SettingsCard(title = "앱 정보") {
            SettingLine("버전", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            SettingLine("패키지", BuildConfig.APPLICATION_ID)
        }

        message?.let { Text(it, color = FeedLoopColors.PrimaryDark) }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
        border = BorderStroke(1.dp, FeedLoopColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = FeedLoopColors.Secondary)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
