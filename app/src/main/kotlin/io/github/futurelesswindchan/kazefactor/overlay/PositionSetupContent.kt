package io.github.futurelesswindchan.kazefactor.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import io.github.futurelesswindchan.kazefactor.engine.vision.Snapper
import io.github.futurelesswindchan.kazefactor.engine.vision.UserFrameInput
import io.github.futurelesswindchan.kazefactor.ui.util.FactorIconMapper
import kotlin.math.abs

/**
 * 全屏手动位置引导与确认预览视图（架构方案第 2.2、3.4.3、3.4.6 节）。
 */
@Composable
fun PositionSetupContent(
    uiState: OverlayUiState,
    onUpdateUserFrame: (UserFrameInput) -> Unit,
    onUpdateTapPoint: (Float, Float) -> Unit,
    onPerformSnap: () -> Unit,
    onProceedToPreview: () -> Unit,
    onCancelSetup: () -> Unit,
    onConfirmAccept: () -> Unit,
    onConfirmReject: () -> Unit
) {
    when (uiState.mode) {
        OverlayMode.MANUAL_SETUP -> {
            val setup = uiState.manualSetup ?: return
            ManualSetupScreen(
                setup = setup,
                onUpdateUserFrame = onUpdateUserFrame,
                onUpdateTapPoint = onUpdateTapPoint,
                onPerformSnap = onPerformSnap,
                onProceedToPreview = onProceedToPreview,
                onCancel = onCancelSetup
            )
        }
        OverlayMode.CONFIRM_PREVIEW -> {
            val preview = uiState.confirmPreview ?: return
            ConfirmPreviewScreen(
                preview = preview,
                onAccept = onConfirmAccept,
                onReject = onConfirmReject,
                onCancel = onCancelSetup
            )
        }
        else -> Unit
    }
}

private enum class DragMode {
    NONE, MOVE_WHOLE, RESIZE_LEFT, RESIZE_RIGHT
}

/**
 * 手动引导全屏界面：
 * 1. 底层展示冻结的游戏截图；
 * 2. 画一条「4 个等大、等间距的方框」轮廓，用户可整体拖动或拉左右两端；
 * 3. 松手后自动触发 [Snapper] 吸附，成功变绿、失败变红；
 * 4. 点击刷新按钮记录点击坐标。
 */
@Composable
private fun ManualSetupScreen(
    setup: ManualSetupData,
    onUpdateUserFrame: (UserFrameInput) -> Unit,
    onUpdateTapPoint: (Float, Float) -> Unit,
    onPerformSnap: () -> Unit,
    onProceedToPreview: () -> Unit,
    onCancel: () -> Unit
) {
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    val latestSetup by rememberUpdatedState(setup)
    val latestOnUpdateUserFrame by rememberUpdatedState(onUpdateUserFrame)
    val latestOnUpdateTapPoint by rememberUpdatedState(onUpdateTapPoint)
    val latestOnPerformSnap by rememberUpdatedState(onPerformSnap)
    val imageBitmap = remember(setup.screenshot) { setup.screenshot.asImageBitmap() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 冻结的游戏画面底图
        Image(
            bitmap = imageBitmap,
            contentDescription = "游戏画面底图",
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize()
        )

        // 2. 交互画布：绘制 4 格轮廓与刷新按钮准星
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        // 若点击点在 4 格轮廓下方区域，则更新刷新按钮点击坐标
                        val current = latestSetup.userFrame
                        val scale = current.estimatedScaleX
                        val frameBottom = current.top +
                            (Snapper.REF_FRAME_BOTTOM - Snapper.REF_FRAME_TOP) * scale
                        if (offset.y > frameBottom + 20f) {
                            latestOnUpdateTapPoint(offset.x, offset.y)
                        }
                    }
                }
                .pointerInput(Unit) {
                    var currentFrame = latestSetup.userFrame
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            // 始终从最新状态（含上次吸附后的校准位置或上次拖放位置）开始拖动
                            currentFrame = latestSetup.userFrame
                            val handleHitRadius = 56f
                            val scale = currentFrame.estimatedScaleX
                            val boxHeight = (Snapper.REF_FRAME_BOTTOM - Snapper.REF_FRAME_TOP) * scale
                            val centerY = currentFrame.top + boxHeight / 2.0

                            dragMode = when {
                                abs(startOffset.x - currentFrame.left) <= handleHitRadius &&
                                    abs(startOffset.y - centerY) <= boxHeight -> DragMode.RESIZE_LEFT
                                abs(startOffset.x - currentFrame.right) <= handleHitRadius &&
                                    abs(startOffset.y - centerY) <= boxHeight -> DragMode.RESIZE_RIGHT
                                startOffset.x in (currentFrame.left - 40)..(currentFrame.right + 40) &&
                                    startOffset.y in (currentFrame.top - 40)..(currentFrame.top + boxHeight + 40) -> DragMode.MOVE_WHOLE
                                else -> DragMode.NONE
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val next = when (dragMode) {
                                DragMode.MOVE_WHOLE -> UserFrameInput(
                                    left = currentFrame.left + dragAmount.x,
                                    right = currentFrame.right + dragAmount.x,
                                    top = currentFrame.top + dragAmount.y
                                )
                                DragMode.RESIZE_LEFT -> {
                                    val newLeft = (currentFrame.left + dragAmount.x)
                                        .coerceAtMost(currentFrame.right - 120.0)
                                    UserFrameInput(newLeft, currentFrame.right, currentFrame.top)
                                }
                                DragMode.RESIZE_RIGHT -> {
                                    val newRight = (currentFrame.right + dragAmount.x)
                                        .coerceAtLeast(currentFrame.left + 120.0)
                                    UserFrameInput(currentFrame.left, newRight, currentFrame.top)
                                }
                                DragMode.NONE -> currentFrame
                            }
                            currentFrame = next
                            latestOnUpdateUserFrame(next)
                        },
                        onDragEnd = {
                            if (dragMode != DragMode.NONE) {
                                dragMode = DragMode.NONE
                                latestOnPerformSnap()
                            }
                        },
                        onDragCancel = {
                            dragMode = DragMode.NONE
                        }
                    )
                }
        ) {
            // 绘制半透明暗色遮罩，突出框选引导
            drawRect(Color.Black.copy(alpha = 0.25f))

            val outlineColor = when {
                setup.snapError != null -> Color(0xFFEF4444) // 红色：未对准
                setup.snappedCalibration != null -> Color(0xFF22C55E) // 绿色：已吸附
                else -> Color(0xFF38BDF8) // 天蓝：拖动中
            }

            // 若已有吸附结果则按吸附后的 Calibration 绘制，否则按用户粗框绘制
            val calib = setup.snappedCalibration
            val edges = ReferenceLayout.SLOT_FRAME_VERTICAL_EDGES
            if (calib != null) {
                val topY = calib.toScreenY(Snapper.REF_FRAME_TOP.toDouble()).toFloat()
                val bottomY = calib.toScreenY(Snapper.REF_FRAME_BOTTOM.toDouble()).toFloat()
                for (box in 0 until ReferenceLayout.SLOT_COUNT) {
                    val leftX = calib.toScreenX(edges[box * 2].position.toDouble()).toFloat()
                    val rightX = calib.toScreenX(edges[box * 2 + 1].position.toDouble()).toFloat()
                    drawRect(
                        color = outlineColor,
                        topLeft = Offset(leftX, topY),
                        size = Size(rightX - leftX, bottomY - topY),
                        style = Stroke(width = 4f)
                    )
                }

                // 即使已吸附，也绘制左右两端的拉手圆点，方便用户继续微调
                val cy = (topY + bottomY) / 2f
                val leftEdgeX = calib.toScreenX(Snapper.REF_FRAME_LEFT.toDouble()).toFloat()
                val rightEdgeX = calib.toScreenX(Snapper.REF_FRAME_RIGHT.toDouble()).toFloat()
                drawCircle(outlineColor, radius = 14f, center = Offset(leftEdgeX, cy))
                drawCircle(outlineColor, radius = 14f, center = Offset(rightEdgeX, cy))
            } else {
                val sx = setup.userFrame.estimatedScaleX
                val leftBase = setup.userFrame.left
                val topY = setup.userFrame.top.toFloat()
                val bottomY = (setup.userFrame.top + (Snapper.REF_FRAME_BOTTOM - Snapper.REF_FRAME_TOP) * sx).toFloat()
                for (box in 0 until ReferenceLayout.SLOT_COUNT) {
                    val refL = edges[box * 2].position - Snapper.REF_FRAME_LEFT
                    val refR = edges[box * 2 + 1].position - Snapper.REF_FRAME_LEFT
                    val lx = (leftBase + refL * sx).toFloat()
                    val rx = (leftBase + refR * sx).toFloat()
                    drawRect(
                        color = outlineColor,
                        topLeft = Offset(lx, topY),
                        size = Size(rx - lx, bottomY - topY),
                        style = Stroke(width = 3.5f)
                    )
                }

                // 左右两端的拉手圆点
                val cy = (topY + bottomY) / 2f
                drawCircle(outlineColor, radius = 14f, center = Offset(setup.userFrame.left.toFloat(), cy))
                drawCircle(outlineColor, radius = 14f, center = Offset(setup.userFrame.right.toFloat(), cy))
            }

            // 绘制刷新按钮点击准星
            val tx = setup.tapScreenX
            val ty = setup.tapScreenY
            val crossColor = Color(0xFFFACC15)
            drawCircle(crossColor, radius = 16f, center = Offset(tx, ty), style = Stroke(width = 3.5f))
            drawLine(crossColor, Offset(tx - 24f, ty), Offset(tx + 24f, ty), strokeWidth = 3f)
            drawLine(crossColor, Offset(tx, ty - 24f), Offset(tx, ty + 24f), strokeWidth = 3f)
        }

        // 3. 左上角操作说明面板（94% 高遮蔽底色 + 单行防挤压按钮，修复 04.jpg）
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .width(336.dp)
                .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(14.dp)),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xF00F172A)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "手动对齐位置（请先打开挑战因子开关）",
                    color = Color(0xFFF8FAFC),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    text = "① 拖动上方 4 格轮廓（或拉左右圆点）盖住 4 个因子方框，松手自动吸附；\n② 点击下方黄色准星对准「刷新按钮」。",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                if (setup.snapError != null) {
                    Text(
                        text = "⚠️ ${setup.snapError}",
                        color = Color(0xFFFDE047),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp
                    )
                } else if (setup.snappedCalibration != null) {
                    Text(
                        text = "✓ 已对准 4 个槽位方框！点击「下一步」核对结果~",
                        color = Color(0xFF4ADE80),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color(0xFFE0F2FE)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(0.85f)
                            .height(36.dp)
                    ) {
                        Text("取消", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onPerformSnap,
                        enabled = !setup.isSnapping,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color(0xFF38BDF8),
                            disabledContainerColor = Color(0x801E293B),
                            disabledContentColor = Color(0xFF64748B)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(36.dp)
                    ) {
                        Text(
                            text = if (setup.isSnapping) "吸附中…" else "🧲 重新吸附",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Button(
                        onClick = onProceedToPreview,
                        enabled = !setup.isSnapping,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (setup.snappedCalibration != null) Color(0xFF22C55E) else Color(0xFF0284C7),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF1E293B),
                            disabledContentColor = Color(0xFF64748B)
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(36.dp)
                    ) {
                        Text(
                            text = "下一步 ➔",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * 确认预览界面（架构方案第 3.4.6 节）：
 * - 截图上画出 4 个槽位的位置；
 * - 展示每个槽位识别出的因子图标与中文名；
 * - 「对」「不对」两个按钮（若有 `Unknown` 或问号则不显示「对」按钮）。
 */
@Composable
private fun ConfirmPreviewScreen(
    preview: ConfirmPreviewData,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit
) {
    val imageBitmap = remember(preview.screenshot) { preview.screenshot.asImageBitmap() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 底图
        Image(
            bitmap = imageBitmap,
            contentDescription = "确认预览底图",
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize()
        )

        // 2. 在截图上画出 4 个槽位框与点击位置
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.35f))
            preview.slotRects.forEachIndexed { idx, rect ->
                val ok = preview.recognizedFactors.getOrNull(idx) != null
                val boxColor = if (ok) Color(0xFF22C55E) else Color(0xFFEF4444)
                drawRect(
                    color = boxColor,
                    topLeft = Offset(rect.x.toFloat(), rect.y.toFloat()),
                    size = Size(rect.width.toFloat(), rect.height.toFloat()),
                    style = Stroke(width = 4f)
                )
            }
            val tx = preview.calibration.toScreenX(preview.tapX).toFloat()
            val ty = preview.calibration.toScreenY(preview.tapY).toFloat()
            drawCircle(Color(0xFFFACC15), radius = 12f, center = Offset(tx, ty), style = Stroke(width = 3f))
        }

        // 3. 确认卡片（放在屏幕左侧，不遮挡右侧槽位画框）
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(20.dp)
                .width(348.dp)
                .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF00F172A)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "请确认识别位置与因子是否正确",
                    color = Color(0xFFF8FAFC),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Text(
                    text = if (preview.canConfirm) {
                        "请核对右侧绿框是否对准 4 个槽位，且下方识别出的 4 个因子与游戏内一致："
                    } else {
                        "⚠️ 存在未识别出的槽位（请确保挑战因子开关已打开且未被遮挡）："
                    },
                    color = if (preview.canConfirm) Color(0xFFCBD5E1) else Color(0xFFFDE047),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                // 4 个槽位识别结果展示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0 until ReferenceLayout.SLOT_COUNT) {
                        val factor = preview.recognizedFactors.getOrNull(i)
                        val slotRes = preview.slotResults.getOrNull(i)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (factor != null) Color(0xFF38BDF8) else Color(0xFFEF4444),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (factor != null) {
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
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            } else {
                                Text(
                                    text = if (slotRes is SlotResult.Question) "问号" else "未识别",
                                    color = Color(0xFFF87171),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        }
                    }
                }

                // 按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color(0xFFE0F2FE)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(0.85f)
                            .height(36.dp)
                    ) {
                        Text("取消", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = onReject,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1.15f)
                            .height(36.dp)
                    ) {
                        Text(
                            text = if (preview.canConfirm) "不对，重调" else "重新调整位置",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (preview.canConfirm) {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1.15f)
                                .height(36.dp)
                        ) {
                            Text(
                                text = "✓ 对，开始",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}
