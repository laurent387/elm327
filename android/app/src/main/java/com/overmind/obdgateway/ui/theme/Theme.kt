package com.overmind.obdgateway.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object OvermindColors {
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF141A22)
    val SurfaceHi = Color(0xFF1C2430)
    val Accent = Color(0xFF28D7FF)
    val Amber = Color(0xFFFFB547)
    val Ok = Color(0xFF3DDC84)
    val Bad = Color(0xFFFF5C5C)
    val Muted = Color(0xFF8A96A8)
    val Text = Color(0xFFE6EDF5)
}

private val scheme = darkColorScheme(
    primary = OvermindColors.Accent,
    onPrimary = Color.Black,
    secondary = OvermindColors.Amber,
    background = OvermindColors.Bg,
    surface = OvermindColors.Surface,
    surfaceVariant = OvermindColors.SurfaceHi,
    onBackground = OvermindColors.Text,
    onSurface = OvermindColors.Text,
    onSurfaceVariant = OvermindColors.Muted,
    error = OvermindColors.Bad,
)

@Composable
fun OvermindTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
