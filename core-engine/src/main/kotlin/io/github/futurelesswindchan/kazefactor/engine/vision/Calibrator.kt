package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.image.rgbAbsDiffSum
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 帮助图标粗定位候选结果。
 *
 * 游戏界面中可能同时存在多个外观相似的帮助图标，粗定位阶段返回按匹配距离升序排列的多个候选，
 * 后续由 [PanelEdgeRefiner] 的面板边框精修与 [Calibrator.verifySlots] 的槽位验证筛选出真实锚点。
 *
 * NOTE: 帮助图标参考宽度仅为 33 px，[scale] 存在数个百分点的离散化误差（图标宽度小于 25 px 时最大约 6%），
 * 仅用于约束面板边框精修的搜索窗口，不可直接用于最终槽位坐标换算。
 *
 * @property scale 粗略等比缩放系数 `s0`
 * @property x 帮助图标左上角在屏幕上的横坐标（像素边界坐标）
 * @property y 帮助图标左上角在屏幕上的纵坐标（像素边界坐标）
 * @property distance 最佳匹配位置处的 RGB 三通道平均绝对差（0~255），越小表示越相似
 */
data class HelpIconEstimate(val scale: Double, val x: Int, val y: Int, val distance: Double) {

    /** 由图标位置推算的粗略横向偏移 `ox0 = x - s0 * 1475`。 */
    val originX: Double get() = x - scale * ReferenceLayout.HELP_ICON_RECT.x

    /** 由图标位置推算的粗略纵向偏移 `oy0 = y - s0 * 125`。 */
    val originY: Double get() = y - scale * ReferenceLayout.HELP_ICON_RECT.y
}

/**
 * 自动校准器：通过「帮助图标粗定位 → 面板边框精修 → 槽位验证」以及「标准居中几何兜底」计算屏幕 [Calibration]。
 *
 * ```
 * val calibrator = Calibrator(helpIconTemplate, recognizerFactory)
 * val calibration = calibrator.calibrate(screenshot) ?: return
 * val recognizer = recognizerFactory.create(calibration)
 * ```
 *
 * NOTE: 本类无可变状态，可在多个协程间安全共享；[recognizerFactory] 的内部模板缓存支持并发访问。
 *
 * @param helpIconTemplate 帮助图标模板图像（`assets/recognition/help.png`），尺寸须与 [ReferenceLayout.HELP_ICON_RECT] 一致
 * @param recognizerFactory 识别器工厂，用于按候选缩放系数生成识别器并执行 4 槽位验证
 * @throws IllegalArgumentException 当模板尺寸与参考布局不符时抛出
 */
open class Calibrator(
    private val helpIconTemplate: ArgbImage,
    private val recognizerFactory: RecognizerFactory
) {

    private val refiner = PanelEdgeRefiner()

    init {
        val r = ReferenceLayout.HELP_ICON_RECT
        require(helpIconTemplate.width == r.width && helpIconTemplate.height == r.height) {
            "帮助图标模板应为 ${r.width}x${r.height}，实际 ${helpIconTemplate.width}x${helpIconTemplate.height}"
        }
    }

    /**
     * 执行完整自动校准。
     *
     * 优先通过「帮助图标粗定位 → 面板边框精修 → 残差检查 → 槽位验证」获取高精度校准参数；
     * 若因高分屏插值、暗角或边框遮挡导致精修未产出可用结果，则自动回退至标准居中几何兜底搜索。
     *
     * @param frame 完整屏幕截图
     * @return 通过槽位验证的校准参数；所有路径均未通过时返回 null
     */
    open fun calibrate(frame: ArgbImage): Calibration? {
        val candidates = locateHelpIcon(frame)
        val refined = refineCandidates(frame, candidates)?.calibration
        if (refined != null) return refined
        return calibrateByGeometricCenterFallback(frame)
    }

    /**
     * 按顺序对粗定位候选列表 [candidates] 执行面板边框精修，返回第一个同时通过残差检查与槽位验证的结果。
     *
     * 包含两道验证门控：
     * 1. 残差检查：横向与纵向拟合残差均不超过 [PanelEdgeRefiner.RefineResult.maxAllowedResidual]。
     * 2. 槽位验证：调用 [verifySlots] 确认 4 个槽位均能识别为有效因子或问号。
     *
     * @param frame 完整屏幕截图，须与生成 [candidates] 的图像为同一帧
     * @param candidates [locateHelpIcon] 返回的粗定位候选列表（按匹配距离升序）
     * @return 通过全部检查的精修结果；无候选通过时返回 null
     */
    fun refineCandidates(frame: ArgbImage, candidates: List<HelpIconEstimate>): PanelEdgeRefiner.RefineResult? =
        candidates.asSequence()
            .mapNotNull { refiner.refine(frame, it) }
            .filter { it.passesResidualCheck }
            .firstOrNull { verifySlots(frame, it.calibration) }

    /**
     * 槽位验证：按 [calibration] 裁取 4 个槽位搜索区域，验证每个槽位均不越界且识别结果不为 [SlotResult.Unknown]。
     *
     * [SlotResult.Ambiguous] 视为通过，因为其表明槽位内确实为合法因子图标，仅存在近似候选待决策展开。
     *
     * @param frame 完整屏幕截图
     * @param calibration 待验证的坐标换算参数
     * @return 4 个槽位均在画面内且识别非未知时返回 true
     */
    fun verifySlots(frame: ArgbImage, calibration: Calibration): Boolean {
        val recognizer = recognizerFactory.create(calibration)
        val inside = (1..ReferenceLayout.SLOT_COUNT).all { slot ->
            val r = recognizer.searchRect(slot)
            r.x >= 0 && r.y >= 0 && r.x + r.width <= frame.width && r.y + r.height <= frame.height
        }
        return inside && recognizer.recognize(frame).slots.none { it == SlotResult.Unknown }
    }

    /**
     * 标准居中几何兜底校准。
     *
     * 当面板边框精修未产出结果时，基于游戏在主流设备上按屏幕高度撑满等比缩放（`scale = height / 1080.0`，`originY = 0.0`）
     * 的渲染规律，围绕理论水平居中偏移 `centerOriginX ± (80.0 * scale)`（覆盖常规居中及单侧挖孔避让偏移）以 4.0 px 步长
     * 扫描候选位置，筛选出通过 [verifySlots] 且 4 槽位平均匹配距离最小的最优解。
     */
    private fun calibrateByGeometricCenterFallback(frame: ArgbImage): Calibration? {
        val scale = frame.height.toDouble() / ReferenceLayout.HEIGHT
        if (scale <= 0.0 || !scale.isFinite()) return null
        val centerOriginX = (frame.width.toDouble() - ReferenceLayout.WIDTH * scale) / 2.0
        val maxOffset = FALLBACK_SEARCH_RANGE_REF * scale
        val maxStepIndex = (maxOffset / FALLBACK_STEP_PX).roundToInt().coerceAtLeast(20)

        var bestCalibration: Calibration? = null
        var bestAvgDistance = Double.POSITIVE_INFINITY

        for (dx in -maxStepIndex..maxStepIndex) {
            val offset = dx * FALLBACK_STEP_PX
            if (kotlin.math.abs(offset) > maxOf(maxOffset, 80.0) + 1e-6) continue
            val ox = centerOriginX + offset
            val candidate = Calibration(scale, scale, ox, 0.0)
            if (!verifySlots(frame, candidate)) continue

            val analyses = recognizerFactory.create(candidate).analyze(frame)
            val avgDistance = analyses.map { slotAnalysis ->
                slotAnalysis.ranking.firstOrNull()?.distance ?: slotAnalysis.questionDistance
            }.average()

            if (avgDistance < bestAvgDistance) {
                bestAvgDistance = avgDistance
                bestCalibration = candidate
            }
        }
        return bestCalibration
    }

    /**
     * 在整帧画面中搜索帮助图标，返回按匹配距离升序排列的候选列表。
     *
     * 算法步骤：
     * 1. 以 `guess = frame.height / 1080.0` 为基准，在 `0.80..1.20` 倍范围内按 `0.02` 步长生成 21 个候选缩放系数。
     * 2. 将画面与模板均缩小至 1/4 分辨率执行全屏粗搜索，经非极大值抑制保留距离最小且互不重叠的前 [MAX_CANDIDATES] 个位置。
     * 3. 映射回原分辨率，在每个粗搜位置周围 `±[FINE_RADIUS] px` 内对全部候选缩放尺寸执行精细搜索。
     *
     * @param frame 完整屏幕截图
     * @return 按 [HelpIconEstimate.distance] 升序排列的候选列表，最多 [MAX_CANDIDATES] 个
     */
    fun locateHelpIcon(frame: ArgbImage): List<HelpIconEstimate> {
        val guess = frame.height.toDouble() / ReferenceLayout.HEIGHT
        val scales = DoubleArray(SCALE_COUNT) { guess * (MIN_SCALE_RATIO + it * SCALE_STEP) }
        return coarseSearch(frame, scales)
            .mapNotNull { (x, y) -> fineSearch(frame, scales, x, y) }
            .sortedBy { it.distance }
    }

    /**
     * 1/4 分辨率全屏粗搜索，返回距离最小、互不重叠的前 [MAX_CANDIDATES] 个原分辨率候选坐标。
     */
    private fun coarseSearch(frame: ArgbImage, scales: DoubleArray): List<Pair<Int, Int>> {
        val smallWidth = frame.width / COARSE_FACTOR
        val smallHeight = frame.height / COARSE_FACTOR
        if (smallWidth < 1 || smallHeight < 1) return emptyList()
        val small = ImageOps.resizeAreaAverage(frame, smallWidth, smallHeight)
        val ratioX = frame.width.toDouble() / smallWidth
        val ratioY = frame.height.toDouble() / smallHeight

        val sizes = scales
            .map { s -> templateSize(s / ratioX, s / ratioY) }
            .distinct()
            .filter { (w, h) -> w <= smallWidth && h <= smallHeight }
        if (sizes.isEmpty()) return emptyList()

        val bestDistance = DoubleArray(smallWidth * smallHeight) { Double.MAX_VALUE }
        val bestSize = IntArray(smallWidth * smallHeight) { -1 }
        sizes.forEachIndexed { sizeIndex, (w, h) ->
            val template = ImageOps.resizeAreaAverage(helpIconTemplate, w, h)
            val denominator = (w * h * 3).toDouble()
            for (y in 0..smallHeight - h) {
                for (x in 0..smallWidth - w) {
                    val i = y * smallWidth + x
                    val limit = if (bestSize[i] < 0) Long.MAX_VALUE else ceil(bestDistance[i] * denominator).toLong()
                    val d = sumAbsDiff(small, template, x, y, limit) / denominator
                    if (d < bestDistance[i]) {
                        bestDistance[i] = d
                        bestSize[i] = sizeIndex
                    }
                }
            }
        }

        val picked = mutableListOf<Pair<Int, Int>>()
        val suppressed = BooleanArray(bestDistance.size)
        repeat(MAX_CANDIDATES) {
            var minIndex = -1
            for (i in bestDistance.indices) {
                if (bestSize[i] < 0 || suppressed[i]) continue
                if (minIndex < 0 || bestDistance[i] < bestDistance[minIndex]) minIndex = i
            }
            if (minIndex < 0) return@repeat
            val px = minIndex % smallWidth
            val py = minIndex / smallWidth
            val (w, h) = sizes[bestSize[minIndex]]
            for (y in maxOf(0, py - h + 1)..minOf(smallHeight - 1, py + h - 1)) {
                for (x in maxOf(0, px - w + 1)..minOf(smallWidth - 1, px + w - 1)) {
                    suppressed[y * smallWidth + x] = true
                }
            }
            picked += (px * ratioX).roundToInt() to (py * ratioY).roundToInt()
        }
        return picked
    }

    /** 在原分辨率下围绕 `([centerX], [centerY]) ± [FINE_RADIUS]` 对各模板尺寸执行精细搜索。 */
    private fun fineSearch(frame: ArgbImage, scales: DoubleArray, centerX: Int, centerY: Int): HelpIconEstimate? {
        var best: HelpIconEstimate? = null
        for ((size, groupScales) in scales.groupBy { templateSize(it, it) }) {
            val (w, h) = size
            if (w > frame.width || h > frame.height) continue
            val template = ImageOps.resizeAreaAverage(helpIconTemplate, w, h)
            val denominator = (w * h * 3).toDouble()
            var limit = best?.let { ceil(it.distance * denominator).toLong() } ?: Long.MAX_VALUE
            var bestX = -1
            var bestY = -1
            for (y in (centerY - FINE_RADIUS).coerceIn(0, frame.height - h)..(centerY + FINE_RADIUS).coerceIn(0, frame.height - h)) {
                for (x in (centerX - FINE_RADIUS).coerceIn(0, frame.width - w)..(centerX + FINE_RADIUS).coerceIn(0, frame.width - w)) {
                    val sum = sumAbsDiff(frame, template, x, y, limit)
                    if (sum < limit) {
                        limit = sum
                        bestX = x
                        bestY = y
                    }
                }
            }
            if (bestX >= 0) best = HelpIconEstimate(groupScales.average(), bestX, bestY, limit / denominator)
        }
        return best
    }

    companion object {
        /** 候选缩放系数相对于初始猜测值的下限比例。 */
        const val MIN_SCALE_RATIO = 0.80

        /** 候选缩放系数相对于初始猜测值的递增步长。 */
        const val SCALE_STEP = 0.02

        /** 候选缩放系数数量（覆盖 `0.80..1.20`）。 */
        const val SCALE_COUNT = 21

        /** 粗搜索阶段图像下采样倍数。 */
        const val COARSE_FACTOR = 4

        /** 精细搜索阶段围绕粗搜位置的搜索半径（单位：px）。 */
        const val FINE_RADIUS = 4

        /** 粗搜索阶段保留的最大非重叠候选数量。 */
        const val MAX_CANDIDATES = 5

        /** 居中几何兜底搜索的基础横向半径（参考坐标系下 80.0 px）。 */
        private const val FALLBACK_SEARCH_RANGE_REF = 80.0

        /** 居中几何兜底搜索的横向步长（单位：px）。 */
        private const val FALLBACK_STEP_PX = 4.0

        /** 计算帮助图标在给定缩放系数下的像素宽高（最小为 1 px）。 */
        private fun templateSize(sx: Double, sy: Double): Pair<Int, Int> {
            val r = ReferenceLayout.HELP_ICON_RECT
            return maxOf(1, (r.width * sx).roundToInt()) to maxOf(1, (r.height * sy).roundToInt())
        }

        /**
         * 计算模板放置于 [image] 的 `([left], [top])` 处时 RGB 三通道绝对差之和，达到 [limit] 时提前终止。
         */
        private fun sumAbsDiff(image: ArgbImage, template: ArgbImage, left: Int, top: Int, limit: Long): Long {
            val tw = template.width
            var sum = 0L
            var row = 0
            while (row < template.height && sum < limit) {
                val srcBase = (top + row) * image.width + left
                val tplBase = row * tw
                for (col in 0 until tw) sum += rgbAbsDiffSum(image.pixels[srcBase + col], template.pixels[tplBase + col])
                row++
            }
            return sum
        }
    }
}
