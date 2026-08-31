package com.youngsu.fieldshare.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = SamsungBlue,
    secondary = SamsungBlueLight,
    tertiary = SuccessGreen
)

private val LightColorScheme = lightColorScheme(
    primary = SamsungBlue,
    secondary = SamsungBlueLight,
    tertiary = SuccessGreen,
    background = SoftGray,
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Ink,
    onSurface = Ink
)

@Composable
fun FieldShareTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
