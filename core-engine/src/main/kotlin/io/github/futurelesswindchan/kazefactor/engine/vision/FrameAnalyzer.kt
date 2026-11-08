package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage

/**
 * 一帧画面的完整分析结果：画面状态 + 4 个槽位的识别结果。
 *
 * @property screenState 画面状态
 * @property slotResults 4 个槽位的识别结果，下标 0~3 对应第 1~4 槽；搜索区域出画面时为全 [SlotResult.Unknown]
 * @throws IllegalArgumentException [slotResults] 不是 4 个时
 */
data class FrameAnalysis(
    val screenState: ScreenState,
    val slotResults: List<SlotResult>
) {
    init {
        require(slotResults.size == ReferenceLayout.SLOT_COUNT) {
            "必须正好有 ${ReferenceLayout.SLOT_COUNT} 个槽位结果，实际 ${slotResults.size} 个"
        }
    }
}

/**
 * 每帧调用一次的分析入口：开关判断 + 槽位识别 → [FrameAnalysis]（架构方案第 4 节第 8 条）。
 *
 * 状态机不直接调用 `ToggleDetector`、`FactorRecognizer`，只和本类打交道。
 * 校准（`Calibrator`）和快速核验（`ProfileVerifier`）每次开始只做一次，由状态机在进入循环前调用，
 * 本类只负责「已知位置后的每一帧」。
 *
 * 开关的判断方式按有没有位置档案区分（架构方案第 3.4.5 节最后一段）：
 * - [analyze]：没有档案（自动校准得到的位置），用绿色像素占比规则。
 * - [analyzeWithProfile]：有档案，和档案里用户屏幕上截下的开关参照块比较。
 *   若距离在 (15.0, 25.0] 浮动区间，自动使用 [ToggleDetector] 绿色占比兜底判定（双保险机制）。
 *
 * @param recognizerFactory 按换算生成识别器，同一缩放的模板会被缓存
 * @param toggleDetector 开关检测器
 * @param profileToggleThreshold 有档案时，开关区域与参照块平均像素差的上限，默认与快速核验相同
 */
open class FrameAnalyzer(
    private val recognizerFactory: RecognizerFactory,
    private val toggleDetector: ToggleDetector = ToggleDetector(),
    private val profileToggleThreshold: Double = ProfileVerifier.DEFAULT_TOGGLE_THRESHOLD
) {

    private val verifier = ProfileVerifier(recognizerFactory, profileToggleThreshold, toggleDetector)

    /**
     * 按 [calibration] 分析一帧，开关用绿色像素占比判断。
     *
     * @param frame 完整截图
     * @param calibration 参考坐标到当前屏幕的换算
     * @return 槽位搜索区域超出画面时为 [NOT_FACTOR_SCREEN_RESULT]
     */
    open fun analyze(frame: ArgbImage, calibration: Calibration): FrameAnalysis =
        analyzeWith(frame, calibration) { toggleDetector.detect(frame, calibration) }

    /**
     * 按位置档案分析一帧，开关与档案里的参照块比较。
     *
     * 距离 ≤ 阈值判为开；处于模糊区间结合 ToggleDetector 兜底；否则判为关。
     * 再交给 [ScreenState.classify]：
     * 「关 + 4 个问号」是开关关闭，「关 + 不是问号」是不在因子界面（或位置已经变了）。
     *
     * @param frame 完整截图
     * @param profile 位置档案；截图尺寸不符时直接返回 [NOT_FACTOR_SCREEN_RESULT]
     */
    open fun analyzeWithProfile(frame: ArgbImage, profile: CalibrationProfile): FrameAnalysis {
        if (!profile.matchesScreen(frame.width, frame.height)) return NOT_FACTOR_SCREEN_RESULT
        return analyzeWith(frame, profile.toCalibration()) {
            val dist = verifier.toggleDistance(frame, profile)
            if (dist <= profileToggleThreshold) {
                ToggleState.ON
            } else if (dist <= ProfileVerifier.TOGGLE_FUZZY_MAX_DISTANCE &&
                toggleDetector.detect(frame, profile.toCalibration()) == ToggleState.ON
            ) {
                ToggleState.ON
            } else {
                ToggleState.OFF
            }
        }
    }

    /** 两条路径共用：检查搜索区域 → 识别 4 槽 → 判断开关 → 综合画面状态。 */
    private inline fun analyzeWith(
        frame: ArgbImage,
        calibration: Calibration,
        toggle: () -> ToggleState
    ): FrameAnalysis {
        val recognizer = recognizerFactory.create(calibration)
        val inside = (1..ReferenceLayout.SLOT_COUNT).all { slot ->
            val r = recognizer.searchRect(slot)
            r.x >= 0 && r.y >= 0 && r.x + r.width <= frame.width && r.y + r.height <= frame.height
        }
        if (!inside) return NOT_FACTOR_SCREEN_RESULT
        val result = recognizer.recognize(frame)
        return FrameAnalysis(ScreenState.classify(toggle(), result), result.slots)
    }

    companion object {
        /** 搜索区域出画面、截图尺寸与档案不符时的结果。 */
        val NOT_FACTOR_SCREEN_RESULT = FrameAnalysis(
            ScreenState.NOT_FACTOR_SCREEN,
            List(ReferenceLayout.SLOT_COUNT) { SlotResult.Unknown }
        )
    }
}
