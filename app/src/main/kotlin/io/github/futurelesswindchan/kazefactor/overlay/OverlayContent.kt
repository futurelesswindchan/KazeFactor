package io.github.futurelesswindchan.kazefactor.overlay

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.futurelesswindchan.kazefactor.OverlayController
import io.github.futurelesswindchan.kazefactor.R
import io.github.futurelesswindchan.kazefactor.engine.loop.PauseReason
import io.github.futurelesswindchan.kazefactor.ui.util.FactorIconMapper
import java.util.Locale

/**
 * 悬浮窗主内容视图：根据 [OverlayUiState.mode] 严格互斥渲染单一面板。
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

    // 严格单面板状态机互斥渲染，同一时刻只存在唯一形态视图
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
                onVibrateChange = { viewModel.updateVibrateOnHit(it) },
                onStartClick = { viewModel.startRefreshing() },
                onManualSetupClick = { viewModel.startManualCalibration() },
                onExportDiagnosticsClick = {
                    viewModel.exportDiagnostics { err ->
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
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
                onManualSetup = { viewModel.startManualCalibration() },
                onExportDiagnostics = {
                    viewModel.exportDiagnostics { err ->
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
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
            .background(Color(0xF00F172A))
            .border(2.dp, Color(0xFF7DD3FC), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
            .clickable { onTap() }
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_emoji),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(34.dp)
        )
    }
}

/**
 * 形态 ②：展开主配置面板（横屏一屏化紧凑布局 + 94% 高遮蔽暗色底）。
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun PanelView(
    uiState: OverlayUiState,
    onDragDelta: (Float, Float) -> Unit,
    onCollapse: () -> Unit,
    onCloseOverlay: () -> Unit,
    onSelectRecipe: (io.github.futurelesswindchan.kazefactor.domain.model.Recipe) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onStartClick: () -> Unit,
    onManualSetupClick: () -> Unit,
    onExportDiagnosticsClick: () -> Unit
) {
    val config = LocalConfiguration.current
    val maxPanelHeight = (config.screenHeightDp - 16).coerceAtLeast(240).dp

    Surface(
        modifier = Modifier
            .width(304.dp)
            .heightIn(max = maxPanelHeight)
            .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF00F172A)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. 顶部标题栏（可拖动 + 胶囊化热区按钮）
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
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                            .clickable { onCollapse() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("收起", color = Color(0xFFE2E8F0), fontSize = 11.sp, maxLines = 1)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF475569), RoundedCornerShape(6.dp))
                            .clickable { onCloseOverlay() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("关闭", color = Color(0xFFF87171), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }

            // 2. 常驻前置条件提示
            Text(
                text = "请在建房界面打开挑战因子开关，并确保没有显示四个问号",
                color = Color(0xFFFDE047),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )

            // 3. 目标配方标题行（合并期望次数与上限，节省纵向空间）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "目标配方",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                if (uiState.selectedRecipe != null && uiState.expectedRefreshCount.isFinite()) {
                    Text(
                        text = "期望 ~${"%.1f".format(Locale.US, uiState.expectedRefreshCount)}次 · 上限 ${uiState.effectiveMaxCount ?: "无"}次",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            // 4. 配方选择列表
            if (uiState.recipes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "暂无配方，请先在主应用内创建配方",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 84.dp),
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
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectRecipe(recipe) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = recipe.name,
                                color = Color(0xFFF8FAFC),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Text("已选", color = Color(0xFF7DD3FC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 5. 命中震动开关行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "刷新频率：约 2.5 次/秒",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    maxLines = 1
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onVibrateChange(!uiState.vibrateOnHit) }
                        .padding(horizontal = 2.dp)
                ) {
                    Checkbox(
                        checked = uiState.vibrateOnHit,
                        onCheckedChange = { onVibrateChange(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF38BDF8),
                            uncheckedColor = Color(0xFF94A3B8),
                            checkmarkColor = Color(0xFF0F172A)
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "命中震动",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            if (uiState.statusMessage != null) {
                Text(
                    text = uiState.statusMessage,
                    color = Color(0xFFFDE047),
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }

            // 6. 主按钮：开始自动刷新
            Button(
                onClick = onStartClick,
                enabled = uiState.selectedRecipe != null && !uiState.isBusyCalibrating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0284C7),
                    contentColor = Color(0xFFF8FAFC),
                    disabledContainerColor = Color(0xFF1E293B),
                    disabledContentColor = Color(0xFF64748B)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            ) {
                Text(
                    text = if (uiState.isBusyCalibrating) "正在准备位置…" else "开始自动刷新",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // 7. 底部辅助按钮：重新校准 + 导出诊断包
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onManualSetupClick,
                    enabled = !uiState.isBusyCalibrating,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color(0xFFE0F2FE),
                        disabledContainerColor = Color(0x801E293B),
                        disabledContentColor = Color(0xFF64748B)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF475569)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Text(
                        text = "重新校准",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                OutlinedButton(
                    onClick = onExportDiagnosticsClick,
                    enabled = !uiState.isBusyCalibrating,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color(0xFFE0F2FE),
                        disabledContainerColor = Color(0x801E293B),
                        disabledContentColor = Color(0xFF64748B)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF475569)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Text(
                        text = "导出诊断包",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * 形态 ③：运行中/暂停小胶囊。
 */
@Composable
private fun RunningCapsuleView(
    uiState: OverlayUiState,
    onDragDelta: (Float, Float) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onManualSetup: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    val paused = uiState.pauseReason != null

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF00F172A),
        modifier = Modifier
            .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val speedText = if (uiState.actualSpeedPerSec > 0f) {
                    "${"%.1f".format(Locale.US, uiState.actualSpeedPerSec)}/s"
                } else {
                    "--/s"
                }
                Text(
                    text = if (paused) {
                        "已刷 ${uiState.refreshCount} 次"
                    } else {
                        "第 ${uiState.refreshCount} 次 ($speedText)"
                    },
                    color = Color(0xFFF8FAFC),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )

                if (!paused) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFFFBBF24), RoundedCornerShape(6.dp))
                            .clickable { onPause() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("暂停", color = Color(0xFFFDE047), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF4ADE80), RoundedCornerShape(6.dp))
                            .clickable { onResume() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("继续", color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFFF87171), RoundedCornerShape(6.dp))
                        .clickable { onStop() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("停止", color = Color(0xFFF87171), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }

            if (uiState.pauseReason != null) {
                Text(
                    text = uiState.pauseReason.toDisplayMessage(),
                    color = Color(0xFFFDE047),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onManualSetup,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color(0xFFE0F2FE)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("手动对齐方框", fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }

                    if (uiState.pauseReason == PauseReason.CONSECUTIVE_FAILURES) {
                        Button(
                            onClick = onExportDiagnostics,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD97706),
                                contentColor = Color(0xFFF8FAFC)
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("导出诊断包", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 形态 ④：命中展开视图（高亮 4 个槽位因子图标 + 「继续刷」「完成」按钮）。
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
            .width(304.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF00F172A)
    ) {
        Column(
            modifier = Modifier
                .border(2.dp, Color(0xFF22C55E), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "命中目标配方（共刷 ${uiState.refreshCount} 次）",
                color = Color(0xFF4ADE80),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color(0xFFE0F2FE)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF475569)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Text("继续刷", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Text("完成", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
