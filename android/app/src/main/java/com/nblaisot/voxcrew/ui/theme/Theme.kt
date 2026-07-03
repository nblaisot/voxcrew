package com.nblaisot.voxcrew.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GreenPrimary = Color(0xFF2E7D32)
private val DarkScheme = darkColorScheme(primary = GreenPrimary)
private val LightScheme = lightColorScheme(primary = GreenPrimary)

@Composable
fun VoxCrewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        content = content,
    )
}
