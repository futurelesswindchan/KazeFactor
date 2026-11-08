package io.github.futurelesswindchan.kazefactor.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import io.github.futurelesswindchan.kazefactor.OverlayController
import io.github.futurelesswindchan.kazefactor.service.ServiceLifecycleOwner
import io.github.futurelesswindchan.kazefactor.ui.theme.KazeFactorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮窗宿主窗口管理器（架构方案第 2.2、5 节）。
 *
 * 职责：
 * 1. 创建 `ComposeView` 并通过 [ServiceLifecycleOwner] 挂载到 `WindowManager`（`TYPE_ACCESSIBILITY_OVERLAY`）；
 * 2. 强制使用暗色主题渲染（解决亮色系统下按钮与文字隐身问题）；
 * 3. 切换全屏形态（手动引导/确认预览为 `MATCH_PARENT`，并穿透全面屏/挖孔安全区）与浮窗形态（`WRAP_CONTENT`）；
 * 4. 支持截屏前临时隐窗避让（alpha = 0f），防止悬浮窗自遮挡游戏画面；
 * 5. 禁区避让（运行时若悬浮窗与因子面板禁区重叠，自动移到禁区外最近的屏幕边缘）。
 */
class OverlayWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val viewModel: OverlayViewModel,
    private val scope: CoroutineScope
) {
    private var composeView: ComposeView? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 记录非全屏状态下的窗口左上角坐标，从全屏恢复时沿用。 */
    private var floatingX: Int = dp(16)
    private var floatingY: Int = dp(16)

    init {
        // 监听是否进入全屏引导模式以及禁区更新
        scope.launch {
            viewModel.uiState
                .map { Triple(it.isFullScreenMode, it.mode, it.forbiddenZone) }
                .distinctUntilChanged()
                .collect { (isFullScreen, mode, forbiddenZone) ->
                    updateWindowLayoutMode(isFullScreen)
                    if (!isFullScreen && mode == OverlayMode.RUNNING && forbiddenZone != null) {
                        evadeForbiddenZoneIfNeeded(forbiddenZone)
                    }
                }
        }
    }

    /** 显示悬浮窗（若已显示则忽略）。 */
    fun show() {
        if (composeView != null) return

        val owner = ServiceLifecycleOwner().also { it.onCreateAndResume() }
        val params = createFloatingLayoutParams()

        val view = ComposeView(context).apply {
            owner.attachTo(this)
            setContent {
                // 强制暗色高对比主题，无论系统处于亮色还是暗色模式，均保证悬浮窗清晰通透
                KazeFactorTheme(darkTheme = true) {
                    OverlayContent(
                        viewModel = viewModel,
                        onDragDelta = { dx, dy -> moveBy(dx, dy) }
                    )
                }
            }
        }

        try {
            windowManager.addView(view, params)
            composeView = view
            lifecycleOwner = owner
            layoutParams = params
            OverlayController.updateActualShowing(true)
        } catch (_: Throwable) {
            owner.onDestroy()
            OverlayController.updateActualShowing(false)
        }
    }

    /** 隐藏并移除悬浮窗。 */
    fun hide() {
        val view = composeView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
        composeView = null
        layoutParams = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        OverlayController.updateActualShowing(false)
    }

    /**
     * 临时隐窗（供截图前避让使用，防止悬浮窗自遮挡游戏画面）。
     * 在主线程同步更新 View 的 alpha 与 visibility，确保下一帧 VSYNC 生效。
     */
    suspend fun setWindowAlpha(alpha: Float) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            composeView?.let { view ->
                view.alpha = alpha
                view.visibility = if (alpha <= 0.01f) android.view.View.INVISIBLE else android.view.View.VISIBLE
            }
        }
    }

    /** 移动悬浮窗位置（仅在非全屏形态下生效）。 */
    private fun moveBy(dx: Float, dy: Float) {
        val view = composeView ?: return
        val params = layoutParams ?: return
        if (viewModel.uiState.value.isFullScreenMode) return

        val metrics = context.resources.displayMetrics
        val maxW = (metrics.widthPixels - view.width.coerceAtLeast(dp(48))).coerceAtLeast(0)
        val maxH = (metrics.heightPixels - view.height.coerceAtLeast(dp(48))).coerceAtLeast(0)

        floatingX = (floatingX + dx.roundToInt()).coerceIn(0, maxW)
        floatingY = (floatingY + dy.roundToInt()).coerceIn(0, maxH)
        params.x = floatingX
        params.y = floatingY

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }

        // 若处于运行中且拖入禁区，自动避让
        val forbidden = viewModel.uiState.value.forbiddenZone
        if (viewModel.uiState.value.mode == OverlayMode.RUNNING && forbidden != null) {
            evadeForbiddenZoneIfNeeded(forbidden)
        }
    }

    /**
     * 切换全屏模式（手动引导/确认预览）与普通浮窗模式。
     *
     * 全屏模式下穿透刘海/挖孔屏（LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS），
     * 确保窗口物理大小与截图 1:1 绝对重合，杜绝挤压变形与画框偏移（实机问题 b 修复）。
     */
    private fun updateWindowLayoutMode(isFullScreen: Boolean) {
        val view = composeView ?: return
        val params = layoutParams ?: return

        if (isFullScreen) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = floatingX
            params.y = floatingY
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    /**
     * 禁区避让（架构方案第 5 节：运行时如果悬浮窗与禁区重叠，自动移到禁区外最近的屏幕边缘）。
     */
    private fun evadeForbiddenZoneIfNeeded(forbidden: Rect) {
        val view = composeView ?: return
        val params = layoutParams ?: return

        val w = view.width.takeIf { it > 0 } ?: dp(200)
        val h = view.height.takeIf { it > 0 } ?: dp(60)
        val windowRect = Rect(floatingX, floatingY, floatingX + w, floatingY + h)

        if (!Rect.intersects(windowRect, forbidden)) return

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val margin = dp(12)

        // 候选避让位置：禁区左侧边缘、禁区上方、屏幕左上角
        val candidateLeftX = (forbidden.left - w - margin).coerceAtLeast(margin)
        val candidateTopY = (forbidden.top - h - margin).coerceAtLeast(margin)

        if (candidateLeftX + w < forbidden.left) {
            // 移到禁区左侧
            floatingX = candidateLeftX
        } else if (candidateTopY + h < forbidden.top) {
            // 移到禁区上方
            floatingY = candidateTopY
        } else {
            // 兜底移到屏幕左上角
            floatingX = margin
            floatingY = margin
        }

        floatingX = floatingX.coerceIn(0, (screenW - w).coerceAtLeast(0))
        floatingY = floatingY.coerceIn(0, (screenH - h).coerceAtLeast(0))
        params.x = floatingX
        params.y = floatingY

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun createFloatingLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = floatingX
            y = floatingY
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
