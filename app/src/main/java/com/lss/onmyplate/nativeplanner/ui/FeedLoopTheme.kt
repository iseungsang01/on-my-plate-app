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
    val Primary = Color(0xFF2563EB)
    val Secondary = Color(0xFF6B7280)
    val Tertiary = Color(0xFFF9FAFB)
    val Background = Color(0xFFF9FAFB)
    val Surface = Color(0xFFFFFFFF)
    val Success = Color(0xFF16A34A)
    val Warning = Color(0xFFD97706)
    val Error = Color(0xFFDC2626)
}

val FeedLoopColorScheme: ColorScheme = lightColorScheme(
    primary = FeedLoopColors.Primary,
    onPrimary = Color.White,
    secondary = FeedLoopColors.Secondary,
    tertiary = FeedLoopColors.Tertiary,
    background = FeedLoopColors.Background,
    onBackground = Color(0xFF111827),
    surface = FeedLoopColors.Surface,
    onSurface = Color(0xFF111827),
    surfaceVariant = FeedLoopColors.Tertiary,
    onSurfaceVariant = FeedLoopColors.Secondary,
    error = FeedLoopColors.Error,
)

@Composable
fun FeedLoopCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = FeedLoopColors.Surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

val FeedLoopCardElevation = 0.dp
