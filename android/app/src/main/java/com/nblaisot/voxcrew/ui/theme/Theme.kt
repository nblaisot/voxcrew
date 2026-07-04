package com.nblaisot.voxcrew.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VoxCrewLightScheme = lightColorScheme(
    primary = VoxOrange,
    onPrimary = Color.White,
    primaryContainer = VoxOrangeContainer,
    onPrimaryContainer = VoxOrangeOnContainer,
    secondary = VoxCharcoal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E1DC),
    onSecondaryContainer = VoxCharcoal,
    tertiary = VoxOrangeLight,
    onTertiary = Color.White,
    background = VoxSurface,
    onBackground = VoxCharcoal,
    surface = VoxSurface,
    onSurface = VoxCharcoal,
    surfaceVariant = VoxSurfaceVariant,
    onSurfaceVariant = Color(0xFF5C5C5C),
    outline = Color(0xFFD7C8BC),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

@Composable
fun VoxCrewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VoxCrewLightScheme,
        content = content,
    )
}
