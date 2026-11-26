package com.dskmusic.vixa.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.dskmusic.vixa.data.AppSettings
import com.dskmusic.vixa.data.ThemeMode

/** Acentos preestablecidos (además del selector HSL libre). */
val AccentPresets: List<Int> = listOf(
    0xFF7C5CFF.toInt(), 0xFF0A84FF.toInt(), 0xFF00B8D4.toInt(), 0xFF30D158.toInt(), 0xFFFFD60A.toInt(),
    0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(), 0xFFFF4F9A.toInt(), 0xFFBF5AF2.toInt(), 0xFF8E8E93.toInt(),
)

private fun hsl(h: Float, s: Float, l: Float): Color = Color(ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/** Esquema Material 3 derivado del color de acento. */
fun vixaScheme(accent: Int, dark: Boolean, oled: Boolean): ColorScheme {
    val a = FloatArray(3)
    ColorUtils.colorToHSL(accent, a)
    val h = a[0]
    val s = a[1]
    val n = minOf(s, 0.14f) // saturación de los neutros teñidos
    return if (dark) {
        val bg = if (oled) Color.Black else hsl(h, n, 0.065f)
        darkColorScheme(
            primary = hsl(h, s, 0.72f), onPrimary = hsl(h, s, 0.12f),
            primaryContainer = hsl(h, s * 0.8f, 0.26f), onPrimaryContainer = hsl(h, s, 0.9f),
            secondary = hsl(h, s * 0.45f, 0.74f), onSecondary = hsl(h, s * 0.45f, 0.14f),
            secondaryContainer = hsl(h, s * 0.4f, 0.22f), onSecondaryContainer = hsl(h, s * 0.45f, 0.9f),
            tertiary = hsl((h + 60f) % 360f, s * 0.6f, 0.74f), onTertiary = hsl((h + 60f) % 360f, s * 0.6f, 0.14f),
            tertiaryContainer = hsl((h + 60f) % 360f, s * 0.5f, 0.24f), onTertiaryContainer = hsl((h + 60f) % 360f, s * 0.6f, 0.9f),
            background = bg, onBackground = hsl(h, n, 0.92f),
            surface = bg, onSurface = hsl(h, n, 0.92f),
            surfaceVariant = hsl(h, n, if (oled) 0.13f else 0.19f), onSurfaceVariant = hsl(h, n, 0.76f),
            surfaceDim = bg, surfaceBright = hsl(h, n, 0.22f),
            surfaceContainerLowest = if (oled) Color.Black else hsl(h, n, 0.045f),
            surfaceContainerLow = hsl(h, n, if (oled) 0.035f else 0.09f),
            surfaceContainer = hsl(h, n, if (oled) 0.055f else 0.11f),
            surfaceContainerHigh = hsl(h, n, if (oled) 0.085f else 0.145f),
            surfaceContainerHighest = hsl(h, n, if (oled) 0.12f else 0.185f),
            outline = hsl(h, n, 0.5f), outlineVariant = hsl(h, n, 0.26f),
            inverseSurface = hsl(h, n, 0.9f), inverseOnSurface = hsl(h, n, 0.15f), inversePrimary = hsl(h, s, 0.4f),
        )
    } else {
        lightColorScheme(
            primary = hsl(h, s, 0.42f), onPrimary = Color.White,
            primaryContainer = hsl(h, s, 0.9f), onPrimaryContainer = hsl(h, s, 0.14f),
            secondary = hsl(h, s * 0.4f, 0.4f), onSecondary = Color.White,
            secondaryContainer = hsl(h, s * 0.5f, 0.9f), onSecondaryContainer = hsl(h, s * 0.5f, 0.14f),
            tertiary = hsl((h + 60f) % 360f, s * 0.6f, 0.4f), onTertiary = Color.White,
            tertiaryContainer = hsl((h + 60f) % 360f, s * 0.6f, 0.9f), onTertiaryContainer = hsl((h + 60f) % 360f, s * 0.6f, 0.14f),
            background = hsl(h, n, 0.985f), onBackground = hsl(h, n, 0.1f),
            surface = hsl(h, n, 0.985f), onSurface = hsl(h, n, 0.1f),
            surfaceVariant = hsl(h, n, 0.9f), onSurfaceVariant = hsl(h, n, 0.3f),
            surfaceDim = hsl(h, n, 0.87f), surfaceBright = hsl(h, n, 0.985f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(h, n, 0.965f),
            surfaceContainer = hsl(h, n, 0.945f),
            surfaceContainerHigh = hsl(h, n, 0.925f),
            surfaceContainerHighest = hsl(h, n, 0.9f),
            outline = hsl(h, n, 0.5f), outlineVariant = hsl(h, n, 0.8f),
            inverseSurface = hsl(h, n, 0.2f), inverseOnSurface = hsl(h, n, 0.95f), inversePrimary = hsl(h, s, 0.75f),
        )
    }
}

fun isDark(settings: AppSettings, systemDark: Boolean): Boolean = when (settings.theme) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.OLED -> true
}

private val VixaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VixaTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDark(settings, isSystemInDarkTheme())
    val scheme = remember(settings.accent, dark, settings.theme) { vixaScheme(settings.accent, dark, settings.theme == ThemeMode.OLED) }
    MaterialTheme(colorScheme = scheme, shapes = VixaShapes, typography = VixaTypography, content = content)
}

fun Color.argb(): Int = toArgb()

/** Pocos tamaños y pesos: título (20/16/14), cuerpo (15/14/12) y etiquetas (13/12/11). */
private val VixaTypography: androidx.compose.material3.Typography = androidx.compose.material3.Typography().let { b ->
    fun st(t: androidx.compose.ui.text.TextStyle, size: Float, w: androidx.compose.ui.text.font.FontWeight) = t.copy(fontSize = androidx.compose.ui.unit.TextUnit(size, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(size * 1.35f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = w)
    val sb = androidx.compose.ui.text.font.FontWeight.SemiBold
    val md = androidx.compose.ui.text.font.FontWeight.Medium
    androidx.compose.material3.Typography(
        titleLarge = st(b.titleLarge, 20f, sb), titleMedium = st(b.titleMedium, 16f, sb), titleSmall = st(b.titleSmall, 14f, sb),
        bodyLarge = st(b.bodyLarge, 15f, androidx.compose.ui.text.font.FontWeight.Normal), bodyMedium = st(b.bodyMedium, 14f, androidx.compose.ui.text.font.FontWeight.Normal), bodySmall = st(b.bodySmall, 12f, androidx.compose.ui.text.font.FontWeight.Normal),
        labelLarge = st(b.labelLarge, 13f, md), labelMedium = st(b.labelMedium, 12f, md), labelSmall = st(b.labelSmall, 11f, md),
    )
}
