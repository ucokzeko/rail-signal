package com.railsignal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RailDarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = OnTeal,
    primaryContainer = TealContainer,
    onPrimaryContainer = OnTealContainer,
    secondary = TextMuted,
    onSecondary = Bg,
    background = Bg,
    onBackground = TextHigh,
    surface = Surface,
    onSurface = TextHigh,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextMuted,
    outline = Outline,
    outlineVariant = OutlineSoft,
    error = SignalDead,
    onError = OnDead,
)

/** Dark-only by the scene sentence (low-light commute, glanceable, OLED-kind). */
@Composable
fun RailSignalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RailDarkColors,
        typography = RailTypography,
        shapes = RailShapes,
        content = content,
    )
}
