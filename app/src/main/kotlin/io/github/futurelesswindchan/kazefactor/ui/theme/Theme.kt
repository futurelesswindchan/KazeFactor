package io.github.futurelesswindchan.kazefactor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * KazeFactor 主题
 * 
 * 支持亮色/暗色主题切换，跟随系统设置
 */

private val LightColorScheme = lightColorScheme(
    primary = SkyBlue,
    onPrimary = Color.White,
    primaryContainer = SkyBlueLight,
    onPrimaryContainer = Color(0xFF003549),
    
    secondary = SkyBlueDark,
    onSecondary = Color.White,
    
    background = LightColors.background,
    onBackground = LightColors.textPrimary,
    
    surface = LightColors.surface1,
    onSurface = LightColors.textPrimary,
    surfaceVariant = LightColors.surface2,
    onSurfaceVariant = LightColors.textSecondary,
    
    error = ErrorRed,
    onError = Color.White,
    
    outline = LightColors.border,
    outlineVariant = LightColors.divider
)

private val DarkColorScheme = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color(0xFF003549),
    primaryContainer = SkyBlueDark,
    onPrimaryContainer = SkyBlueLight,
    
    secondary = SkyBlueLight,
    onSecondary = Color(0xFF003549),
    
    background = DarkColors.background,
    onBackground = DarkColors.textPrimary,
    
    surface = DarkColors.surface1,
    onSurface = DarkColors.textPrimary,
    surfaceVariant = DarkColors.surface2,
    onSurfaceVariant = DarkColors.textSecondary,
    
    error = ErrorRed,
    onError = Color.White,
    
    outline = DarkColors.border,
    outlineVariant = DarkColors.divider
)

/**
 * KazeFactor 主题入口
 * 
 * @param darkTheme 是否使用暗色主题（默认跟随系统）
 * @param content 子组件
 */
@Composable
fun KazeFactorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = KazeTypography,
        content = content
    )
}

/**
 * 扩展属性：获取当前主题的额外语义色
 */
val ColorScheme.success: Color
    @Composable get() = SuccessGreen

val ColorScheme.warning: Color
    @Composable get() = WarningYellow

val ColorScheme.textTertiary: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkColors.textTertiary else LightColors.textTertiary

val ColorScheme.surface3: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkColors.surface3 else LightColors.surface3
