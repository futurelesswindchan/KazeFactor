package io.github.futurelesswindchan.kazefactor.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * KazeFactor 主题色板
 * 
 * 支持亮色/暗色主题切换：
 * - 亮色主题：浅灰背景
 * - 暗色主题：墨蓝背景
 * - 主色调：天蓝色系
 */

// 天蓝色主色调（统一）
val SkyBlue = Color(0xFF38BDF8)        // 主色：天蓝
val SkyBlueLight = Color(0xFF7DD3FC)   // 浅天蓝（hover/按压态）
val SkyBlueDark = Color(0xFF0EA5E9)    // 深天蓝（强调/选中）

// 语义色（统一）
val SuccessGreen = Color(0xFF6EE7B7)   // 成功/想要
val WarningYellow = Color(0xFFFBBF24)  // 警告/可选
val ErrorRed = Color(0xFFF87171)       // 错误/不想要

// 亮色主题 - 浅灰色系表面
object LightColors {
    val background = Color(0xFFF5F5F7)       // 浅灰背景
    val surface1 = Color(0xFFFFFFFF)         // 卡片/主要表面
    val surface2 = Color(0xFFFAFAFA)         // 次级表面
    val surface3 = Color(0xFFF0F0F0)         // 三级表面（分割线等）
    
    val textPrimary = Color(0xFF1A1A1A)      // 主要文字
    val textSecondary = Color(0xFF666666)    // 次要文字
    val textTertiary = Color(0xFF999999)     // 三级文字（提示等）
    
    val border = Color(0xFFE0E0E0)           // 边框
    val divider = Color(0xFFEEEEEE)          // 分割线
}

// 暗色主题 - 墨蓝色系表面
object DarkColors {
    val background = Color(0xFF0A0E1A)       // 墨蓝背景（接近纯黑但带蓝紫调）
    val surface1 = Color(0xFF141B2E)         // 卡片/主要表面（提升一级）
    val surface2 = Color(0xFF1A2236)         // 次级表面（提升二级）
    val surface3 = Color(0xFF22293D)         // 三级表面（提升三级）
    
    val textPrimary = Color(0xFFE8E8E8)      // 主要文字
    val textSecondary = Color(0xFFB0B0B0)    // 次要文字
    val textTertiary = Color(0xFF808080)     // 三级文字（提示等）
    
    val border = Color(0xFF2A3448)           // 边框
    val divider = Color(0xFF1E2636)          // 分割线
}
