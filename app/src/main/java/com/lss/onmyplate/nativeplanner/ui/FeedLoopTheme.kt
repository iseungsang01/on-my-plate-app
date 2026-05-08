package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object FeedLoopColors {
    val Primary = Color(0xFFF4A261)
    val PrimaryDark = Color(0xFFD9823B)
    val PrimaryLight = Color(0xFFFFE3C2)
    val Secondary = Color(0xFF6F6258)
    val Accent = Color(0xFFF6C453)
    val Tertiary = Color(0xFFFFF3E0)
    val Background = Color(0xFFFFFBF4)
    val Surface = Color(0xFFFFFFFF)
    val Elevated = Color(0xFFFFF8ED)
    val Success = Color(0xFF2F6B3F)
    val SuccessBg = Color(0xFFEAF7ED)
    val SuccessBorder = Color(0xFFB9D8C2)
    val Warning = Color(0xFF8A6400)
    val WarningBg = Color(0xFFFFF6D9)
    val WarningBorder = Color(0xFFEFD47A)
    val Error = Color(0xFFA94442)
    val ErrorBg = Color(0xFFFFF0ED)
    val ErrorBorder = Color(0xFFE8B4B0)
    val Pending = Color(0xFF5D547A)
    val PendingBg = Color(0xFFF0EEF7)
    val PendingBorder = Color(0xFFD5CDE8)
    val Done = Color(0xFF2E5E8C)
    val TextPrimary = Color(0xFF2F2924)
    val TextMuted = Color(0xFFA89C91)
    val Border = Color(0xFFE8D9C7)
    val BorderMuted = Color(0xFFF1E8DD)
}

val FeedLoopColorScheme: ColorScheme = lightColorScheme(
    primary = FeedLoopColors.Primary,
    onPrimary = Color.White,
    secondary = FeedLoopColors.Secondary,
    tertiary = FeedLoopColors.Tertiary,
    background = FeedLoopColors.Background,
    onBackground = FeedLoopColors.TextPrimary,
    surface = FeedLoopColors.Surface,
    onSurface = FeedLoopColors.TextPrimary,
    surfaceVariant = FeedLoopColors.Tertiary,
    onSurfaceVariant = FeedLoopColors.Secondary,
    error = FeedLoopColors.Error,
    outline = FeedLoopColors.Border,
    outlineVariant = FeedLoopColors.BorderMuted,
)

@Composable
fun FeedLoopCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = FeedLoopColors.Surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

val FeedLoopCardElevation = 3.dp
