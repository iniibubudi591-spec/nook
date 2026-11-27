package com.nook.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class ReaderTheme(
    val key: String,
    val label: String,
    val bg: Long,
    val fg: Long,
    val link: Long,
) {
    fun isDark(): Boolean = key == "dark" || key == "black"
}

val ReaderThemes = listOf(
    ReaderTheme("light", "Putih Kertas", 0xFFFAF9F6, 0xFF1F1D1A, 0xFF8C4A27),
    ReaderTheme("sepia", "Sepia Hangat", 0xFFF4ECD8, 0xFF3B2F23, 0xFF8C4A27),
    ReaderTheme("dark", "Gelap Nyaman", 0xFF1C1B19, 0xFFE6E1D8, 0xFFE5A97E),
    ReaderTheme("black", "Hitam OLED", 0xFF000000, 0xFFD8D4CC, 0xFFE5A97E),
)

fun readerTheme(key: String): ReaderTheme =
    ReaderThemes.firstOrNull { it.key == key } ?: ReaderThemes[1]

val HighlightColors = listOf(
    "#FFF59D", // Kuning hangat
    "#C8E6C9", // Hijau sage
    "#BBDEFB", // Biru lembut
    "#F8BBD0", // Merah muda pastel
)

private val WarmLightColors = lightColorScheme(
    primary = Color(0xFF8C4A27),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6DFD2),
    onPrimaryContainer = Color(0xFF381604),
    secondary = Color(0xFF5D534A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBE0D6),
    onSecondaryContainer = Color(0xFF1E1711),
    background = Color(0xFFF8F5F0),
    onBackground = Color(0xFF1E1B18),
    surface = Color(0xFFFCF9F5),
    onSurface = Color(0xFF1E1B18),
    surfaceVariant = Color(0xFFEDE6DC),
    onSurfaceVariant = Color(0xFF504840),
    outlineVariant = Color(0xFFD6CCC0),
)

private val WarmDarkColors = darkColorScheme(
    primary = Color(0xFFE5A97E),
    onPrimary = Color(0xFF421D07),
    primaryContainer = Color(0xFF5F3218),
    onPrimaryContainer = Color(0xFFF6DFD2),
    secondary = Color(0xFFD0C4B8),
    onSecondary = Color(0xFF352E27),
    secondaryContainer = Color(0xFF4B433B),
    onSecondaryContainer = Color(0xFFEBE0D6),
    background = Color(0xFF161412),
    onBackground = Color(0xFFEAE3DA),
    surface = Color(0xFF1E1B18),
    onSurface = Color(0xFFEAE3DA),
    surfaceVariant = Color(0xFF2D2823),
    onSurfaceVariant = Color(0xFFD0C5B9),
    outlineVariant = Color(0xFF4A423A),
)

private val NookTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun NookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) WarmDarkColors else WarmLightColors,
        typography = NookTypography,
        content = content,
    )
}