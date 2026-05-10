package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.auth.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(authRepository: AuthRepository, onAuthenticated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isSignUp by remember { mutableStateOf(false) }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canSubmit = identifier.isNotBlank() && password.isNotBlank() && (!isSignUp || passwordConfirm.isNotBlank())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(FeedLoopColors.Background, FeedLoopColors.PrimaryLight.copy(alpha = 0.35f)),
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
            border = BorderStroke(1.dp, FeedLoopColors.Border),
            elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("온마이플레이트", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (isSignUp) "아이디와 비밀번호로 계정을 만들고 시작하세요." else "계정이 있으면 아이디와 비밀번호로 로그인하세요.",
                    color = FeedLoopColors.Secondary,
                )

                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it.trim() },
                    label = { Text("아이디") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FeedLoopColors.PrimaryDark,
                        unfocusedBorderColor = FeedLoopColors.Border,
                        focusedLabelColor = FeedLoopColors.PrimaryDark,
                        cursorColor = FeedLoopColors.PrimaryDark,
                    ),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("비밀번호") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FeedLoopColors.PrimaryDark,
                        unfocusedBorderColor = FeedLoopColors.Border,
                        focusedLabelColor = FeedLoopColors.PrimaryDark,
                        cursorColor = FeedLoopColors.PrimaryDark,
                    ),
                )
                if (isSignUp) {
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = { passwordConfirm = it },
                        label = { Text("비밀번호 확인") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FeedLoopColors.PrimaryDark,
                            unfocusedBorderColor = FeedLoopColors.Border,
                            focusedLabelColor = FeedLoopColors.PrimaryDark,
                            cursorColor = FeedLoopColors.PrimaryDark,
                        ),
                    )
                }

                message?.let { Text(it, color = FeedLoopColors.Error) }

                Button(
                    onClick = {
                        scope.launch {
                            if (isSignUp && password != passwordConfirm) {
                                message = "비밀번호 확인이 일치하지 않습니다."
                                return@launch
                            }
                            loading = true
                            message = null
                            try {
                                if (isSignUp) authRepository.signUp(identifier, password) else authRepository.login(identifier, password)
                                onAuthenticated()
                            } catch (t: Throwable) {
                                message = t.message ?: "인증 처리 중 오류가 발생했습니다."
                            } finally {
                                loading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSubmit && !loading && authRepository.isConfigured(),
                    colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
                ) {
                    Text(if (loading) "처리 중..." else if (isSignUp) "회원가입" else "로그인")
                }

                OutlinedButton(
                    onClick = {
                        authRepository.enterGuestMode()
                        onAuthenticated()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("게스트로 시작")
                }

                TextButton(
                    onClick = {
                        isSignUp = !isSignUp
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isSignUp) "이미 계정이 있나요? 로그인" else "계정이 없나요? 회원가입")
                }

                if (!authRepository.isConfigured()) {
                    Text(
                        "PLANNER_API_BASE_URL이 설정되지 않아 로그인할 수 없습니다.",
                        color = FeedLoopColors.Error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
