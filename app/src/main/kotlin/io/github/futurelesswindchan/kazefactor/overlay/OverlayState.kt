package io.github.futurelesswindchan.kazefactor.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.engine.loop.PauseReason
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibration
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfile
import io.github.futurelesswindchan.kazefactor.engine.vision.PixelRect
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import io.github.futurelesswindchan.kazefactor.engine.vision.UserFrameInput
import kotlin.math.roundToInt

/**
 * 悬浮窗的 UI 形态枚举（架构方案第 2.2、3.4.3、3.4.6、5 节）。
 */
enum class OverlayMode {
    /** 小球形态：可自由拖动，点击展开为面板。 */
    BUBBLE,

    /** 面板形态：选择配方、调整频率/震动、点击开始、重新设置位置、保存当前画面。 */
    PANEL,

    /** 运行中/暂停胶囊形态：展示刷新次数、实际速度、期望次数、暂停原因与恢复/重设入口。 */
    RUNNING,

    /** 命中形态：展开展示 4 个槽位的因子图标与中文名，提供「继续刷」「完成」按钮。 */
    HIT,

    /** 全屏手动引导形态：底层显示干净的游戏截图，用户在上面拖 4 格轮廓、点一下刷新按钮并自动吸附。 */
    MANUAL_SETUP,

    /** 全屏确认预览形态：显示 4 个槽位画框与识别出的因子，用户点「对」保存档案或「不对」进入手动引导。 */
    CONFIRM_PREVIEW
}

/**
 * 确认预览界面所需的数据（架构方案第 3.4.6 节）。
 *
 * @property screenshot 预览底图（干净截图）
 * @property calibration 待确认的位置换算
 * @property source 来源（`AUTO` 自动校准 / `MANUAL` 手动引导）
 * @property tapX 刷新按钮点击位置的参考横坐标
 * @property tapY 刷新按钮点击位置的参考纵坐标
 * @property slotRects 4 个槽位在屏幕上的像素框（用于在底图上画框）
 * @property slotResults 4 个槽位的识别结果
 * @property recognizedFactors 4 个槽位解析出的因子（若某槽为 `Ambiguous` 取第一个候选，若为 `Unknown` 或 `Question` 则为 null）
 * @property startAfterConfirm 用户点「对」保存档案后，是否立即启动自动刷新
 */
data class ConfirmPreviewData(
    val screenshot: Bitmap,
    val calibration: Calibration,
    val source: CalibrationProfile.Source,
    val tapX: Double,
    val tapY: Double,
    val slotRects: List<PixelRect>,
    val slotResults: List<SlotResult>,
    val recognizedFactors: List<Factor?>,
    val startAfterConfirm: Boolean
) {
    /**
     * 是否允许用户点击「对」（架构方案第 3.4.6 节：如果有槽位识别成 `Unknown` 或 `Question`，
     * 不显示「对」按钮，只显示「不对，重新设置」，保证保存下来的档案至少完整认出过一次 4 个因子）。
     */
    val canConfirm: Boolean
        get() = slotResults.none { it is SlotResult.Unknown || it is SlotResult.Question }
}

/**
 * 手动位置引导界面所需的数据（架构方案第 3.4.3、3.4.4 节）。
 *
 * @property screenshot 干净的游戏截图（截图前已隐藏悬浮窗）
 * @property userFrame 当前 4 格轮廓的屏幕边界坐标（left, right, top）
 * @property tapScreenX 用户点选的刷新按钮屏幕横坐标
 * @property tapScreenY 用户点选的刷新按钮屏幕纵坐标
 * @property snappedCalibration 最近一次吸附成功的换算（非空时界面展示绿色吸附轮廓）
 * @property snapError 最近一次吸附失败的提示文案（非空时轮廓变红）
 * @property isSnapping 是否正在后台计算吸附
 * @property startAfterConfirm 完成确认预览后是否立即启动自动刷新
 */
data class ManualSetupData(
    val screenshot: Bitmap,
    val userFrame: UserFrameInput,
    val tapScreenX: Float,
    val tapScreenY: Float,
    val snappedCalibration: Calibration? = null,
    val snapError: String? = null,
    val isSnapping: Boolean = false,
    val startAfterConfirm: Boolean = false
)

/**
 * 悬浮窗整体 UI 状态。
 */
data class OverlayUiState(
    val mode: OverlayMode = OverlayMode.BUBBLE,
    val recipes: List<Recipe> = emptyList(),
    val selectedRecipe: Recipe? = null,
    val expectedRefreshCount: Double = Double.POSITIVE_INFINITY,
    val frequencyPerSecond: Int = 3,
    val actualMaxCapPerSecond: Float = 2.8f,
    val maxCountLimitEnabled: Boolean = true,
    val effectiveMaxCount: Int? = 100,
    val vibrateOnHit: Boolean = true,
    val hasProfileForCurrentScreen: Boolean = false,
    val isBusyCalibrating: Boolean = false,
    val statusMessage: String? = null,
    val refreshCount: Int = 0,
    val failureCount: Int = 0,
    val actualSpeedPerSec: Float = 0f,
    val pauseReason: PauseReason? = null,
    val hitFactors: List<Factor> = emptyList(),
    val manualSetup: ManualSetupData? = null,
    val confirmPreview: ConfirmPreviewData? = null,
    /** 当前校准得到的屏幕禁区（标题 + 4 个槽位 + 刷新按钮，外扩 24dp），供 `OverlayWindow` 自动避让。 */
    val forbiddenZone: Rect? = null
) {
    /** 是否处于全屏引导或确认预览模式（此时窗口铺满全屏且不响应拖动）。 */
    val isFullScreenMode: Boolean
        get() = mode == OverlayMode.MANUAL_SETUP || mode == OverlayMode.CONFIRM_PREVIEW
}

/**
 * 计算禁区矩形（架构方案第 5 节：标题 + 4 个槽位 + 刷新按钮的屏幕矩形，各外扩 24 dp）。
 *
 * 参考坐标范围：
 * - 左边界：第 1 槽外框左沿 1070（含刷新按钮长条左端 1070）
 * - Top：帮助图标/标题上沿 125
 * - 右边界：刷新按钮长条右端 1700 / 第 4 槽外框右沿 1680 → 取 1700
 * - Bottom：刷新按钮长条下沿 480
 */
fun computeForbiddenZone(calibration: Calibration, marginPx: Int): Rect {
    val left = (calibration.toScreenX(1070.0).roundToInt() - marginPx).coerceAtLeast(0)
    val top = (calibration.toScreenY(125.0).roundToInt() - marginPx).coerceAtLeast(0)
    val right = calibration.toScreenX(1700.0).roundToInt() + marginPx
    val bottom = calibration.toScreenY(480.0).roundToInt() + marginPx
    return Rect(left, top, right, bottom)
}

/**
 * 将暂停原因转换为用户友好的中文说明（架构方案第 3.1、3.3、3.4.2、4 节）。
 */
fun PauseReason.toDisplayMessage(): String = when (this) {
    PauseReason.CALIBRATION_FAILED -> "请确认停在挑战因子界面且开关已打开"
    PauseReason.TOGGLE_OFF -> "请先打开挑战因子开关，再点「继续」"
    PauseReason.NOT_FACTOR_SCREEN -> "未检测到挑战因子界面（已切出游戏或被遮挡）"
    PauseReason.CONSECUTIVE_FAILURES -> "连续 3 次识别失败，请检查画面是否被遮挡或重新设置位置"
    PauseReason.TAP_INEFFECTIVE -> "点击刷新未生效（连续 3 次结果相同），请检查刷新按钮位置"
    PauseReason.MAX_REFRESH_COUNT -> "已达到本次刷新的总次数上限"
    PauseReason.VERIFY_FAILED -> "位置快速核验未通过，正在尝试重新定位…"
    PauseReason.USER_PAUSED -> "已暂停"
}
