package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.auth.AuthRepository
import com.lss.onmyplate.nativeplanner.data.supabase.FeedbackRepository
import com.lss.onmyplate.nativeplanner.data.supabase.SharingRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    authRepository: AuthRepository,
    sharingRepository: SharingRepository,
    feedbackRepository: FeedbackRepository,
    onLoggedOut: () -> Unit = {},
) {
    var sessionPresent by remember { mutableStateOf(sharingRepository.hasCachedSession()) }
    var publicId by remember { mutableStateOf(sharingRepository.cachedPublicId().orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPasswordConfirm by remember { mutableStateOf("") }
    var changingPassword by remember { mutableStateOf(false) }
    var passwordChangeExpanded by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf("") }
    var sendingFeedback by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackMessageColor by remember { mutableStateOf(FeedLoopColors.Secondary) }
    val scope = rememberCoroutineScope()

    val loginState = when {
        sessionPresent -> "세션 있음"
        else -> "로그인 필요"
    }
    val canChangePassword = sessionPresent && authRepository.isConfigured() && !changingPassword
    val canSendFeedback = feedbackRepository.isConfigured() && !sendingFeedback

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
        Text("로그인, 피드백, 앱 정보를 관리합니다.", color = FeedLoopColors.Secondary)

        SettingsCard(title = "계정 관리") {
            SettingLine("내 공유 ID", publicId.ifBlank { "생성 전" })
            SettingLine("로그인 상태", loginState)
            Button(
                onClick = {
                    passwordChangeExpanded = !passwordChangeExpanded
                    message = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canChangePassword,
            ) {
                Text(if (passwordChangeExpanded) "비밀번호 변경 접기" else "비밀번호 변경")
            }
            AnimatedVisibility(
                visible = passwordChangeExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = { currentPassword = it },
                        label = { Text("현재 비밀번호") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = canChangePassword,
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("새 비밀번호") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = canChangePassword,
                    )
                    OutlinedTextField(
                        value = newPasswordConfirm,
                        onValueChange = { newPasswordConfirm = it },
                        label = { Text("새 비밀번호 확인") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = canChangePassword,
                    )
                    Button(
                        onClick = {
                            when {
                                currentPassword.isBlank() -> message = "현재 비밀번호를 입력하세요."
                                newPassword.length < 6 -> message = "새 비밀번호는 6자 이상이어야 합니다."
                                newPassword != newPasswordConfirm -> message = "새 비밀번호와 확인 값이 일치하지 않습니다."
                                else -> {
                                    changingPassword = true
                                    message = null
                                    scope.launch {
                                        runCatching {
                                            authRepository.changePassword(currentPassword, newPassword)
                                        }.onSuccess {
                                            currentPassword = ""
                                            newPassword = ""
                                            newPasswordConfirm = ""
                                            message = "비밀번호를 변경했습니다."
                                        }.onFailure { error ->
                                            message = error.message ?: "비밀번호 변경에 실패했습니다."
                                        }
                                        changingPassword = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canChangePassword,
                    ) {
                        Text(if (changingPassword) "변경 중..." else "변경 확인")
                    }
                }
            }
            if (!canChangePassword && !changingPassword) {
                Text("로그인 세션과 인증 API가 있을 때만 비밀번호를 변경할 수 있습니다.", color = FeedLoopColors.Secondary)
            }
            OutlinedButton(
                onClick = {
                    authRepository.clearAccess()
                    sharingRepository.clearAccountCache()
                    sessionPresent = false
                    publicId = ""
                    message = "저장된 세션을 삭제했습니다."
                    onLoggedOut()
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, FeedLoopColors.Border),
            ) {
                Text("로그아웃")
            }
        }

        SettingsCard(title = "피드백 남기기") {
            Text(
                "버그 신고, 개선 제안, 사용 중 불편한 점을 남겨주세요.",
                color = FeedLoopColors.Secondary,
            )
            OutlinedTextField(
                value = feedbackText,
                onValueChange = {
                    feedbackText = it
                    feedbackMessage = null
                },
                label = { Text("피드백 내용") },
                placeholder = { Text("어떤 점이 불편했는지, 어떻게 바뀌면 좋을지 적어주세요.") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 6,
                enabled = canSendFeedback,
            )
            Button(
                onClick = {
                    val trimmed = feedbackText.trim()
                    if (trimmed.isBlank()) {
                        feedbackMessage = "피드백 내용을 입력하세요."
                        feedbackMessageColor = FeedLoopColors.Error
                        return@Button
                    }
                    sendingFeedback = true
                    feedbackMessage = null
                    scope.launch {
                        runCatching {
                            feedbackRepository.submitFeedback(trimmed)
                        }.onSuccess {
                            feedbackText = ""
                            feedbackMessage = "피드백을 전송했습니다."
                            feedbackMessageColor = FeedLoopColors.PrimaryDark
                        }.onFailure { error ->
                            feedbackMessage = error.message ?: "피드백 전송에 실패했습니다."
                            feedbackMessageColor = FeedLoopColors.Error
                        }
                        sendingFeedback = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSendFeedback && feedbackText.isNotBlank(),
            ) {
                Text(if (sendingFeedback) "전송 중..." else "피드백 보내기")
            }
            if (!feedbackRepository.isConfigured()) {
                Text("피드백 기능을 사용하려면 PLANNER_API_BASE_URL 설정이 필요합니다.", color = FeedLoopColors.Secondary)
            }
            feedbackMessage?.let { Text(it, color = feedbackMessageColor) }
        }

        SettingsCard(title = "앱 정보") {
            SettingLine("버전", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
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
