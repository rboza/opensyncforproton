package dev.rboza.android.opensyncforproton.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's own violet Material 3 scheme, light or dark as the system is, on every phone: not the wallpaper's dynamic
 * colours.
 */
@Composable
fun OspTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D4AFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E2FF),
    onPrimaryContainer = Color(0xFF24135F),
    secondary = Color(0xFF5E5873),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E0F4),
    onSecondaryContainer = Color(0xFF1C1730),
    tertiary = Color(0xFF7A4F00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBEBC8),
    onTertiaryContainer = Color(0xFF2B1A00),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E2),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F4FB),
    onBackground = Color(0xFF1B1726),
    surface = Color(0xFFF6F4FB),
    onSurface = Color(0xFF1B1726),
    surfaceVariant = Color(0xFFE6E0F0),
    onSurfaceVariant = Color(0xFF4F4965),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1EDF9),
    surfaceContainerHighest = Color(0xFFEAE5F5),
    outline = Color(0xFF79738C),
    outlineVariant = Color(0xFFE0DAEC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA895FF),
    onPrimary = Color(0xFF1E1258),
    primaryContainer = Color(0xFF3B2E85),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFFC9C2DD),
    onSecondary = Color(0xFF312C42),
    secondaryContainer = Color(0xFF3A3450),
    onSecondaryContainer = Color(0xFFE6E0F4),
    tertiary = Color(0xFFF4C066),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF3D2F12),
    onTertiaryContainer = Color(0xFFFBDFA6),
    error = Color(0xFFFF9C9C),
    onError = Color(0xFF561D22),
    errorContainer = Color(0xFF4A1E24),
    onErrorContainer = Color(0xFFFFDADC),
    background = Color(0xFF14121B),
    onBackground = Color(0xFFECE9F5),
    surface = Color(0xFF14121B),
    onSurface = Color(0xFFECE9F5),
    surfaceVariant = Color(0xFF332E40),
    onSurfaceVariant = Color(0xFFB9B3CF),
    surfaceContainerLowest = Color(0xFF100E16),
    surfaceContainerLow = Color(0xFF1F1C28),
    surfaceContainer = Color(0xFF1F1C28),
    surfaceContainerHigh = Color(0xFF2A2635),
    surfaceContainerHighest = Color(0xFF342F41),
    outline = Color(0xFF938DA9),
    outlineVariant = Color(0xFF332E40),
)
