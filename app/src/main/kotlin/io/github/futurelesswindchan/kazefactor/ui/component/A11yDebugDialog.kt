package io.github.futurelesswindchan.kazefactor.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.service.KazeAccessibilityService

/**
 * 第 5 步临时无障碍调试弹窗（架构方案第 9 节第 5 步；第 6 步替换为正式悬浮窗与权限引导页）。
 *
 * 提供：
 * 1. 无障碍服务连接状态与「打开系统无障碍设置」快捷跳转。
 * 2. 开启/关闭左上角 `TYPE_ACCESSIBILITY_OVERLAY` 迷你调试条（推荐在游戏内直接点悬浮条按钮测试）。
 * 3. 三项调试动作（在弹窗内点击会自动延迟 3 秒执行，留出切回游戏的时间）。
 * 4. 实时日志与测速报告展示，支持一键复制到剪贴板。
 */
@Composable
fun A11yDebugDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isConnected by KazeAccessibilityService.isConnected.collectAsState()
    val isOverlayShowing by KazeAccessibilityService.isOverlayShowing.collectAsState()
    val fgPkg by KazeAccessibilityService.foregroundPackage.collectAsState()
    val logText by KazeAccessibilityService.debugLog.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("第 5 步 · 无障碍真机调试") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isConnected) "🟢 无障碍服务已连接" else "🔴 无障碍服务未连接",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                if (fgPkg != null) {
                    Text(
                        text = "最近前台包名：$fgPkg",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("前往系统「无障碍」设置")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示游戏内调试悬浮条", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "左上角 TYPE_ACCESSIBILITY_OVERLAY，可在游戏内直接点按钮",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isOverlayShowing,
                        enabled = isConnected,
                        onCheckedChange = { checked ->
                            KazeAccessibilityService.instance?.setDebugOverlayVisible(checked)
                        }
                    )
                }

                Text(
                    text = "或在下方点击（将延迟 3 秒执行，请点后立即切回游戏）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            Toast.makeText(context, "3 秒后截图并保存，请切回游戏", Toast.LENGTH_SHORT).show()
                            KazeAccessibilityService.instance?.debugCaptureOnce(delayMs = 3_000L)
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("① 截1张")
                    }
                    Button(
                        onClick = {
                            Toast.makeText(context, "3 秒后点击刷新，请切回游戏", Toast.LENGTH_SHORT).show()
                            KazeAccessibilityService.instance?.debugTapRefreshOnce(delayMs = 3_000L)
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("② 点刷新")
                    }
                }

                Button(
                    onClick = {
                        Toast.makeText(context, "3 秒后开始连截 20 次，请切回游戏", Toast.LENGTH_SHORT).show()
                        KazeAccessibilityService.instance?.debugCapture20Times(delayMs = 3_000L)
                    },
                    enabled = isConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("③ 连续截图 20 次（测最小间隔与失败码）")
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("输出日志 / 测速报告：", style = MaterialTheme.typography.labelLarge)
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("KazeFactorDebugLog", logText))
                            Toast.makeText(context, "已复制调试报告", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("复制报告")
                    }
                }

                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp)
                ) {
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
