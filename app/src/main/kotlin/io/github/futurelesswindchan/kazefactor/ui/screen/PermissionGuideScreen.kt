package io.github.futurelesswindchan.kazefactor.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.OverlayController
import io.github.futurelesswindchan.kazefactor.service.KazeAccessibilityService

/**
 * 权限引导与「悬浮窗模式」启动界面（架构方案第 2.2、7 节，产品规格 2026-11-08）。
 *
 * 重点覆盖：
 * 1. Step 1 → Step 2 纵向全宽按钮流（消除双按钮并排换行挤压，修复 `06.jpg`）；
 * 2. Android 13+「受限设置」解除引导（直达应用信息页）；
 * 3. 国产系统后台省电限制文字提示；
 * 4. Android 8~10（< API 30）不支持无障碍自动刷新的友好说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuideScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isServiceConnected by KazeAccessibilityService.isConnected.collectAsState()
    val isOverlayShowing by OverlayController.isOverlayShowing.collectAsState()
    val sdkInt = Build.VERSION.SDK_INT
    val isSupportedSdk = sdkInt >= Build.VERSION_CODES.R

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "自动刷新 · 权限与悬浮窗",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Android 版本兼容性提示
            if (!isSupportedSdk) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "⚠️ 当前系统版本暂不支持自动刷新",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "无障碍截图功能需要 Android 11（API 30）及以上系统，您的设备为 Android ${Build.VERSION.RELEASE}（API $sdkInt）。您仍可正常使用配方管理与口令码分享功能。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 2. 当前服务与悬浮窗状态卡片（Step 1 -> Step 2 纵向全宽按钮流）
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "无障碍服务状态",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (isServiceConnected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = if (isServiceConnected) "已就绪 ✓" else "未开启",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isServiceConnected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "本工具通过 Android 无障碍服务完成屏幕识别与自动点击，不联网、无需额外申请悬浮窗权限。",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    val openAccessibilitySettings = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }

                    // 步骤 ①：开启无障碍服务
                    if (!isServiceConnected) {
                        Button(
                            onClick = openAccessibilitySettings,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "① 前往无障碍设置开启服务",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = openAccessibilitySettings,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "✓ ① 无障碍服务已开启（点击可重设）",
                                maxLines = 1
                            )
                        }
                    }

                    // 步骤 ②：开启/收起悬浮窗模式
                    if (!isOverlayShowing) {
                        Button(
                            onClick = { OverlayController.requestShow() },
                            enabled = isServiceConnected && isSupportedSdk,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isServiceConnected) {
                                    "🚀 ② 开启悬浮窗模式"
                                } else {
                                    "② 开启悬浮窗（需先完成步骤①）"
                                },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { OverlayController.requestHide() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "✨ 悬浮球运行中（点击收起悬浮窗）",
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 3. Android 13+「受限设置」说明卡片（架构方案第 7 节重点要求）
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "① 开关是灰色的 / 提示「受限设置」？（Android 13+）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "从浏览器或文件管理器直接安装的 APK（如从 GitHub 下载），Android 13 及以上系统会默认限制开启无障碍服务。\n\n解决步骤：\n1. 点击下方「打开本应用信息页」；\n2. 点击页面右上角的「⋮」菜单（部分系统在页面最底部）；\n3. 选择「允许受限设置」并验证锁屏密码；\n4. 返回无障碍设置即可正常打开「KazeFactor 自动刷新」。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "打开本应用信息页（解除受限设置）",
                            maxLines = 1
                        )
                    }
                }
            }

            // 4. 国产系统后台保活提示（架构方案第 7 节）
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "② 防止系统后台自动关闭服务",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "部分系统（OriginOS、MIUI/HyperOS、ColorOS、HarmonyOS 等）会在切入游戏后清理后台，导致无障碍服务自己关掉。\n\n建议在系统设置中将 KazeFactor：\n• 电池/省电策略设为「无限制 / 允许后台高耗电」；\n• 在最近任务列表中给 KazeFactor 加锁。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 5. 使用流程快速指南
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "③ 游戏内使用步骤",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "1. 开启无障碍服务后，点击上方「开启悬浮窗模式」，屏幕左上角会出现「风」悬浮球；\n" +
                            "2. 进入《元气骑士》联机模式 → 打开「挑战因子」配置界面，并把下方绿色挑战因子开关打开；\n" +
                            "3. 点击「风」悬浮球展开面板，选择配方并点击「▶ 开始自动刷新」；\n" +
                            "4. 首次使用会自动识别位置并弹出确认预览，核对 4 个因子无误后点击「✓ 对，开始」即可自动刷到命中！",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
