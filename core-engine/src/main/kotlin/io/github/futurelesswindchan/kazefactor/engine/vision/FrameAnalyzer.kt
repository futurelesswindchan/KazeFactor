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
 * 一帧画面的详细诊断分析结果：在 [FrameAnalysis] 基础上附带每个槽位的候选匹配排行榜与开关参照块距离。
 *
 * 供真机诊断包采集与离线排查使用，保留各槽位的完整候选打分、最佳偏移与二次比较优势。
 *
 * @property analysis 基础画面状态与 4 个槽位结果
 * @property slotAnalyses 4 个槽位的完整匹配过程；搜索区域超出画面或截图尺寸不符时为空列表
 * @property toggleDistance 使用位置档案核验时，开关区域与参照块的平均像素差；未使用位置档案时为 null
 */
data class DetailedFrameAnalysis(
    val analysis: FrameAnalysis,
    val slotAnalyses: List<SlotAnalysis>,
    val toggleDistance: Double? = null
)

/**
 * 每帧调用一次的分析入口：开关判断 + 槽位识别 → [FrameAnalysis]。
 *
 * 状态机不直接调用 `ToggleDetector`、`FactorRecognizer`，只和本类交互。
 * 校准（`Calibrator`）和快速核验（`ProfileVerifier`）每次开始只做一次，由状态机在进入循环前调用，
 * 本类只负责已知位置后的每一帧分析。
 *
 * 开关的判断方式按是否存在位置档案区分：
 * - [analyze]：没有档案（自动校准得到的位置），使用绿色像素占比规则。
 * - [analyzeWithProfile]：有档案，与档案里截下的开关参照块比较；
 *   若距离处于 (15.0, 25.0] 浮动区间，自动使用 [ToggleDetector] 绿色占比兜底判定。
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
     * 距离小于等于阈值判为开；处于模糊区间结合 [ToggleDetector] 兜底；否则判为关。
     * 再交给 [ScreenState.classify]：
     * 关且 4 个槽位均为问号时判为开关关闭，关且不全是问号时判为不在因子界面。
     *
     * @param frame 完整截图
     * @param profile 位置档案；截图尺寸不符时直接返回 [NOT_FACTOR_SCREEN_RESULT]
     */
    open fun analyzeWithProfile(frame: ArgbImage, profile: CalibrationProfile): FrameAnalysis {
        if (!profile.matchesScreen(frame.width, frame.height)) return NOT_FACTOR_SCREEN_RESULT
        return analyzeWith(frame, profile.toCalibration()) {
            resolveProfileToggleState(frame, profile).first
        }
    }

    /**
     * 按 [calibration] 对一帧执行详细诊断分析，保留 4 个槽位的完整候选匹配排行。
     *
     * @param frame 完整截图
     * @param calibration 参考坐标到当前屏幕的换算
     * @return 包含 [FrameAnalysis] 与各槽位 [SlotAnalysis] 排行榜的详细结果
     */
    open fun analyzeDetailed(frame: ArgbImage, calibration: Calibration): DetailedFrameAnalysis =
        analyzeDetailedWith(frame, calibration, toggleDistance = null) {
            toggleDetector.detect(frame, calibration)
        }

    /**
     * 按位置档案 [profile] 对一帧执行详细诊断分析，保留 4 个槽位的完整候选匹配排行及开关参照块距离。
     *
     * @param frame 完整截图
     * @param profile 位置档案；截图尺寸不符时返回 [NOT_FACTOR_SCREEN_DETAILED_RESULT]
     * @return 包含 [FrameAnalysis]、各槽位 [SlotAnalysis] 排行榜与 `toggleDistance` 的详细结果
     */
    open fun analyzeDetailedWithProfile(
        frame: ArgbImage,
        profile: CalibrationProfile
    ): DetailedFrameAnalysis {
        if (!profile.matchesScreen(frame.width, frame.height)) {
            return NOT_FACTOR_SCREEN_DETAILED_RESULT
        }
        val (toggleState, dist) = resolveProfileToggleState(frame, profile)
        return analyzeDetailedWith(frame, profile.toCalibration(), toggleDistance = dist) {
            toggleState
        }
    }

    /** 计算位置档案下的开关状态与参照块距离。 */
    private fun resolveProfileToggleState(
        frame: ArgbImage,
        profile: CalibrationProfile
    ): Pair<ToggleState, Double> {
        val dist = verifier.toggleDistance(frame, profile)
        val state = if (dist <= profileToggleThreshold) {
            ToggleState.ON
        } else if (dist <= ProfileVerifier.TOGGLE_FUZZY_MAX_DISTANCE &&
            toggleDetector.detect(frame, profile.toCalibration()) == ToggleState.ON
        ) {
            ToggleState.ON
        } else {
            ToggleState.OFF
        }
        return state to dist
    }

    /** 两条常规路径共用：检查搜索区域 → 识别 4 槽 → 判断开关 → 综合画面状态。 */
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

    /** 两条详细诊断路径共用：检查搜索区域 → 完整分析 4 槽（fullAnalysis = true） → 综合画面状态。 */
    private inline fun analyzeDetailedWith(
        frame: ArgbImage,
        calibration: Calibration,
        toggleDistance: Double?,
        toggle: () -> ToggleState
    ): DetailedFrameAnalysis {
        val recognizer = recognizerFactory.create(calibration)
        val inside = (1..ReferenceLayout.SLOT_COUNT).all { slot ->
            val r = recognizer.searchRect(slot)
            r.x >= 0 && r.y >= 0 && r.x + r.width <= frame.width && r.y + r.height <= frame.height
        }
        if (!inside) {
            return DetailedFrameAnalysis(
                analysis = NOT_FACTOR_SCREEN_RESULT,
                slotAnalyses = emptyList(),
                toggleDistance = toggleDistance
            )
        }
        val slotAnalyses = recognizer.analyze(frame, fullAnalysis = true)
        val frameResult = FrameResult(slotAnalyses.map { it.result })
        val frameAnalysis = FrameAnalysis(ScreenState.classify(toggle(), frameResult), frameResult.slots)
        return DetailedFrameAnalysis(
            analysis = frameAnalysis,
            slotAnalyses = slotAnalyses,
            toggleDistance = toggleDistance
        )
    }

    companion object {
        /** 搜索区域出画面、截图尺寸与档案不符时的结果。 */
        val NOT_FACTOR_SCREEN_RESULT = FrameAnalysis(
            ScreenState.NOT_FACTOR_SCREEN,
            List(ReferenceLayout.SLOT_COUNT) { SlotResult.Unknown }
        )

        /** 搜索区域出画面、截图尺寸与档案不符时的详细分析结果。 */
        val NOT_FACTOR_SCREEN_DETAILED_RESULT = DetailedFrameAnalysis(
            analysis = NOT_FACTOR_SCREEN_RESULT,
            slotAnalyses = emptyList(),
            toggleDistance = Double.POSITIVE_INFINITY
        )
    }
}