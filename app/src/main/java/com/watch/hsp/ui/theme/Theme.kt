package com.watch.hsp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = HspPrimary,
    onPrimary = HspSurface,
    primaryContainer = HspPrimaryContainer,
    onPrimaryContainer = HspPrimary,
    background = HspBackground,
    onBackground = HspOnSurface,
    surface = HspSurface,
    onSurface = HspOnSurface,
    surfaceVariant = HspSurfaceVariant,
    onSurfaceVariant = HspOnSurfaceVariant,
    outline = HspOutline,
    error = HspError
)

@Composable
fun HspTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
