package com.plaintextfiles.explorer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.plaintextfiles.explorer.model.ThemeMode

@Immutable
data class PlainTextPalette(
    val mode: ThemeMode,
    val name: String,
    val isDark: Boolean,
    val background: Color,
    val backgroundDark: Color,
    val backgroundLight: Color,
    val currentLine: Color,
    val lineHighlight: Color,
    val selection: Color,
    val foreground: Color,
    val comment: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val green: Color,
    val cyan: Color,
    val purple: Color,
    val pink: Color
)

val DraculaPalette = PlainTextPalette(
    mode = ThemeMode.DRACULA,
    name = "Dracula",
    isDark = true,
    background = Color(0xFF282A36),
    backgroundDark = Color(0xFF21222C),
    backgroundLight = Color(0xFF343746),
    currentLine = Color(0xFF6272A4),
    lineHighlight = Color(0xFF353747),
    selection = Color(0xFF44475A),
    foreground = Color(0xFFF8F8F2),
    comment = Color(0xFF6272A4),
    red = Color(0xFFFF5555),
    orange = Color(0xFFFFB86C),
    yellow = Color(0xFFF1FA8C),
    green = Color(0xFF50FA7B),
    cyan = Color(0xFF8BE9FD),
    purple = Color(0xFFBD93F9),
    pink = Color(0xFFFF79C6)
)

val AlucardPalette = PlainTextPalette(
    mode = ThemeMode.ALUCARD,
    name = "Alucard",
    isDark = false,
    background = Color(0xFFFFFBEB),
    backgroundDark = Color(0xFFDEDCCF),
    backgroundLight = Color(0xFFEFEDDC),
    currentLine = Color(0xFF6C664B),
    lineHighlight = Color(0xFFE2DECA),
    selection = Color(0xFFCFCFDE),
    foreground = Color(0xFF1F1F1F),
    comment = Color(0xFF6C664B),
    red = Color(0xFFCB3A2A),
    orange = Color(0xFFA34D14),
    yellow = Color(0xFF846E15),
    green = Color(0xFF14710A),
    cyan = Color(0xFF036A96),
    purple = Color(0xFF644AC9),
    pink = Color(0xFFA3144D)
)

val LocalPlainTextPalette = staticCompositionLocalOf { DraculaPalette }

val PlainTextTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

@Composable
fun PlainTextTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val palette = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DraculaPalette else AlucardPalette
        ThemeMode.DRACULA -> DraculaPalette
        ThemeMode.ALUCARD -> AlucardPalette
    }

    val colorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.purple,
            onPrimary = palette.background,
            secondary = palette.cyan,
            onSecondary = palette.background,
            tertiary = palette.pink,
            background = palette.background,
            onBackground = palette.foreground,
            surface = palette.backgroundDark,
            onSurface = palette.foreground,
            error = palette.red,
            onError = palette.background
        )
    } else {
        lightColorScheme(
            primary = palette.purple,
            onPrimary = palette.background,
            secondary = palette.cyan,
            onSecondary = palette.background,
            tertiary = palette.pink,
            background = palette.background,
            onBackground = palette.foreground,
            surface = palette.backgroundLight,
            onSurface = palette.foreground,
            error = palette.red,
            onError = palette.background
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = palette.backgroundDark.toArgb()
            window.navigationBarColor = palette.backgroundDark.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !palette.isDark
            controller.isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    CompositionLocalProvider(LocalPlainTextPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PlainTextTypography,
            content = content
        )
    }
}
