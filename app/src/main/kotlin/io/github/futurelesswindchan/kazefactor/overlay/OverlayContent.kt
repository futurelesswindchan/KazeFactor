package io.github.futurelesswindchan.kazefactor.overlay

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.futurelesswindchan.kazefactor.OverlayController
import io.github.futurelesswindchan.kazefactor.ui.util.FactorIconMapper
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 悬浮窗主内容视图：根据 [OverlayUiState.mode] 渲染「小球 / 面板 / 运行中 / 命中 / 全屏引导」（架构方案第 2.2、5 节）。
 *
 * @param viewModel 悬浮窗 ViewModel
 * @param onDragDelta 用户拖动小球或胶囊时的像素位移回调（由 `OverlayWindow` 更新 `WindowManager.LayoutParams`）
 */
@Composable
fun OverlayContent(
    viewModel: OverlayViewModel,
    onDragDelta: (Float, Float) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    when (uiState.mode) {
        OverlayMode.BUBBLE -> {
            BubbleView(
                onTap = { viewModel.toggleBubblePanel() },
                onDragDelta = onDragDelta
            )
        }
        OverlayMode.PANEL -> {
            PanelView(
                uiState = uiState,
                onDragDelta = onDragDelta,
                onCollapse = { viewModel.collapseToBubble() },
                onCloseOverlay = { OverlayController.requestHide() },
                onSelectRecipe = { viewModel.selectRecipe(it) },
                onFrequencyChange = { viewModel.updateFrequency(it) },
                onVibrateChange = { viewModel.updateVibrateOnHit(it) },
                onStartClick = { viewModel.startRefreshing() },
                onManualSetupClick = { viewModel.startManualCalibration() },
                onSaveScreenshotClick = {
                    viewModel.saveCurrentScreenShot { path ->
                        Toast.makeText(context, "画面已保存：$path", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
        OverlayMode.RUNNING -> {
            RunningCapsuleView(
                uiState = uiState,
                onDragDelta = onDragDelta,
                onPause = { viewModel.pauseRefreshing() },
                onResume = { viewModel.resumeRefreshing() },
                onStop = { viewModel.stopRefreshing() },
                onManualSetup = { viewModel.startManualCalibration() }
            )
        }
        OverlayMode.HIT -> {
            HitExpandedView(
                uiState = uiState,
                onDragDelta = onDragDelta,
                onContinue = { viewModel.continueRefreshingAfterHit() },
                onFinish = { viewModel.finishHit() }
            )
        }
        OverlayMode.MANUAL_SETUP, OverlayMode.CONFIRM_PREVIEW -> {
            PositionSetupContent(
                uiState = uiState,
                onUpdateUserFrame = { viewModel.updateManualUserFrame(it) },
                onUpdateTapPoint = { x, y -> viewModel.updateManualTapPoint(x, y) },
                onPerformSnap = { viewModel.performSnap() },
                onProceedToPreview = { viewModel.proceedToConfirmPreviewFromManual() },
                onCancelSetup = { viewModel.cancelManualSetup() },
                onConfirmAccept = { viewModel.onConfirmPreviewAccept() },
                onConfirmReject = { viewModel.onConfirmPreviewReject() }
            )
        }
    }
}

/**
 * 形态 ①：悬浮小球（可拖动，点击展开面板）。
 */
@Composable
private fun BubbleView(
    onTap: () -> Unit,
    onDragDelta: (Float, Float) -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xE60284C7))
            .border(2.dp, Color(0xFF7DD3FC), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "风",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}

/**
 * 形态 ②：展开面板（选配方、设频率、开始、重设位置、保存当前画面）。
 */
@Composable
private fun PanelView(
    uiState: OverlayUiState,
    onDragDelta: (Float, Float) -> Unit,
    onCollapse: () -> Unit,
    onCloseOverlay: () -> Unit,
    onSelectRecipe: (io.github.futurelesswindchan.kazefactor.domain.model.Recipe) -> Unit,
    onFrequencyChange: (Int) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onStartClick: () -> Unit,
    onManualSetupClick: () -> Unit,
    onSaveScreenshotClick: () -> Unit
) {
    Surface(
        modifier = Modifier.width(290.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF20F172A)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 顶部标题栏（可拖动）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.x, dragAmount.y)
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "KazeFactor 自动刷新",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCollapse) {
                        Text("收起", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    TextButton(onClick = onCloseOverlay) {
                        Text("×", color = Color(0xFFF87171), fontSize = 14.sp)
                    }
                }
            }

            // 配方选择列表
            Text(
                text = "选择目标配方：",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp
            )
            if (uiState.recipes.isEmpty()) {
                Text(
                    text = "暂无配方，请先在主应用内创建配方~",
                    color = Color(0xFFFBBF24),
                    fontSize = 12.sp
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.recipes, key = { it.id }) { recipe ->
                        val isSelected = recipe.id == uiState.selectedRecipe?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) Color(0xFF0369A1) else Color(0xFF1E293B)
                                )
                                .clickable { onSelectRecipe(recipe) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = recipe.name,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (isSelected) {
                                Text("✓", color = Color(0xFF7DD3FC), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 期望次数提示
            if (uiState.selectedRecipe != null && uiState.expectedRefreshCount.isFinite()) {
                Text(
                    text = "期望约 ${"%.1f".format(Locale.US, uiState.expectedRefreshCount)} 次 · 上限 ${uiState.effectiveMaxCount ?: "无"} 次",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }

            // 频率滑块（1~10 次/秒，展示当前截图方式实际能达到的上限）
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "频率：${uiState.frequencyPerSecond} 次/秒",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "无障碍上限 ≈ ${"%.1f".format(Locale.US, uiState.actualMaxCapPerSecond)}/s",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Slider(
                    value = uiState.frequencyPerSecond.toFloat(),
                    onValueChange = { onFrequencyChange(it.roundToInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.height(28.dp)
                )
            }

            // 命中震动开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onVibrateChange(!uiState.vibrateOnHit) }
            ) {
                Checkbox(
                    checked = uiState.vibrateOnHit,
                    onCheckedChange = { onVibrateChange(it) },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("命中时短震动提醒", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }

            if (uiState.statusMessage != null) {
                Text(
                    text = uiState.statusMessage,
                    color = Color(0xFFFBBF24),
                    fontSize = 11.sp
                )
            }

            // 主按钮：开始自动刷新
            Button(
                onClick = onStartClick,
                enabled = uiState.selectedRecipe != null && !uiState.isBusyCalibrating,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (uiState.isBusyCalibrating) "正在校准位置…" else "开始自动刷新",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // 底部辅助按钮：重新设置位置 + 保存当前画面
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onManualSetupClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("重设位置", color = Color(0xFF7DD3FC), fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onSaveScreenshotClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存当前画面", color = Color(0xFFCBD5E1), fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * 形态 ③：运行中/暂停小胶囊（展示刷新次数、速度、暂停原因与操作按钮）。
 */
@Composable
private fun RunningCapsuleView(
    uiState: OverlayUiState,
    onDragDelta: (Float, Float) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onManualSetup: () -> Unit
) {
    val paused = uiState.pauseReason != null

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xE60F172A),
        modifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val speedText = if (uiState.actualSpeedPerSec > 0f) {
                    "${"%.1f".format(Locale.US, uiState.actualSpeedPerSec)}/s"
                } else {
                    "--/s"
                }
                Text(
                    text = if (paused) {
                        "⏸ 已刷 ${uiState.refreshCount} 次"
                    } else {
                        "🔄 第 ${uiState.refreshCount} 次 ($speedText)"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                if (!paused) {
                    TextButton(onClick = onPause) {
                        Text("暂停", color = Color(0xFFFBBF24), fontSize = 12.sp)
                    }
                } else {
                    TextButton(onClick = onResume) {
                        Text("继续", color = Color(0xFF4ADE80), fontSize = 12.sp)
                    }
                }

                TextButton(onClick = onStop) {
                    Text("停止", color = Color(0xFFF87171), fontSize = 12.sp)
                }
            }

            if (uiState.pauseReason != null) {
                Text(
                    text = uiState.pauseReason.toDisplayMessage(),
                    color = Color(0xFFFBBF24),
                    fontSize = 11.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onManualSetup) {
                        Text("手动设置位置", color = Color(0xFF38BDF8), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/**
 * 形态 ④：命中展开视图（高亮 4 个槽位因子图标 + 「继续刷」「完成」按钮，架构方案第 1.5、5 节）。
 */
@Composable
private fun HitExpandedView(
    uiState: OverlayUiState,
    onDragDelta: (Float, Float) -> Unit,
    onContinue: () -> Unit,
    onFinish: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(300.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF20F172A)
    ) {
        Column(
            modifier = Modifier
                .border(2.dp, Color(0xFF22C55E), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "🎉 命中目标配方！（共刷 ${uiState.refreshCount} 次）",
                color = Color(0xFF4ADE80),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                uiState.hitFactors.forEach { factor ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF4ADE80), RoundedCornerShape(8.dp))
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val painter = FactorIconMapper.rememberFactorPainter(factor)
                        if (painter != null) {
                            Image(
                                painter = painter,
                                contentDescription = factor.name,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = factor.name,
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("继续刷", color = Color(0xFF7DD3FC), fontSize = 12.sp)
                }
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("完成", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
