package com.bitperfect.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianBg = Color(0xFF0B0D12)
val SlateSurface = Color(0xFF151922)
val SlateElevated = Color(0xFF1E2430)
val BitPerfectGold = Color(0xFFF5A623)
val DirectUsbCyan = Color(0xFF38BDF8)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val SuccessGreen = Color(0xFF22C55E)

private val AudiophileColorScheme = darkColorScheme(
    primary = BitPerfectGold,
    onPrimary = Color.Black,
    secondary = DirectUsbCyan,
    onSecondary = Color.Black,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = TextSecondary
)

@Composable
fun BitPerfectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AudiophileColorScheme,
        content = content
    )
}
