package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.blueOf
import io.github.futurelesswindchan.kazefactor.engine.image.greenOf
import io.github.futurelesswindchan.kazefactor.engine.image.redOf
import kotlin.math.roundToInt

/**
 * 手动位置引导中用户拖拽的 4 槽外框轮廓参数（屏幕像素边界坐标）。
 *
 * 用户仅调整轮廓的左右两端与整体位置，高度随宽度按参考比例等比变化。
 * [left] 与 [right] 分别对应第 1 槽外框左沿（参考坐标 x = 1076）与第 4 槽外框右沿（参考坐标 x = 1680），
 * [top] 对应槽位外框上沿（参考坐标 y = 236）。
 *
 * @property left 轮廓左沿屏幕横坐标
 * @property right 轮廓右沿屏幕横坐标，须大于 [left]
 * @property top 轮廓上沿屏幕纵坐标
 * @throws IllegalArgumentException 当 [right] 不大于 [left] 时抛出
 */
data class UserFrameInput(val left: Double, val right: Double, val top: Double) {
    init {
        require(right > left) { "轮廓右沿必须在左沿右侧：left = $left，right = $right" }
    }

    /** 由轮廓宽度推算的等比缩放初值。 */
    val estimatedScaleX: Double
        get() = (right - left) / (Snapper.REF_FRAME_RIGHT - Snapper.REF_FRAME_LEFT)

    companion object {
        /**
         * 根据给定 [calibration] 生成对应的初始轮廓坐标。
         */
        fun fromCalibration(calibration: Calibration): UserFrameInput = UserFrameInput(
            left = calibration.toScreenX(Snapper.REF_FRAME_LEFT.toDouble()),
            right = calibration.toScreenX(Snapper.REF_FRAME_RIGHT.toDouble()),
            top = calibration.toScreenY(Snapper.REF_FRAME_TOP.toDouble())
        )
    }
}

/**
 * 手动引导的边缘吸附器：将用户粗略对齐的 4 格轮廓微调吸附至真实的槽位方框边缘并生成 [Calibration]。
 *
 * 游戏界面采用等比渲染，4 个槽位始终为等大、等间距的正方形，因此横向与纵向共用单一等比缩放系数 `scale`（`scaleX == scaleY`）。
 *
 * 算法步骤：
 * 1. 尺度与横向搜索：基于 8 条竖边，在初值 [UserFrameInput.estimatedScaleX] 的 ±[SCALE_SEARCH_RANGE]（步长 [SCALE_SEARCH_STEP]）与左沿位置 ±[OFFSET_SEARCH_RANGE] px（步长 1 px）范围内搜索 `scale` 与 `left`。
 * 2. 纵向搜索：固定第 1 步得到的 `scale` 与 `left`，基于 8 条横边，在上沿位置 ±[OFFSET_SEARCH_RANGE] px（步长 1 px）范围内搜索 `top`。
 * 3. 联合精修：综合 16 条边，在 `scale`（±[REFINE_SCALE_RANGE]，步长 [REFINE_SCALE_STEP]）、`left`（±[REFINE_OFFSET_RANGE] px，步长 [REFINE_OFFSET_STEP] px）、`top`（±[REFINE_OFFSET_RANGE] px，步长 [REFINE_OFFSET_STEP] px）空间内联合精修。
 * 4. 逐边检查：校验 16 条边各自的采样覆盖率是否均达到 [MIN_EDGE_COVERAGE]，未达标则判定吸附失败。
 *
 * 搜索参数以屏幕上的左沿与上沿坐标为基准点，避免改变缩放系数时轮廓整体漂移。
 * 边缘梯度截断至 ±[GRADIENT_CLAMP]，以消除槽位内部高对比度图标纹理对边缘评分的干扰。
 */
class Snapper {

    /**
     * 边缘吸附结果。
     *
     * @property calibration 吸附得到的等比几何换算参数
     * @property edgeCoverages 16 条边各自的覆盖率：前 8 项为竖边（按 x 升序），后 8 项为横边（各槽位上沿与下沿）
     */
    data class SnapResult(val calibration: Calibration, val edgeCoverages: List<Double>) {
        /** 16 条边中的最低覆盖率。 */
        val worstCoverage: Double get() = edgeCoverages.minOrNull() ?: 0.0

        /** 是否所有边的覆盖率均达到 [MIN_EDGE_COVERAGE]。 */
        val passesEdgeCheck: Boolean get() = worstCoverage >= MIN_EDGE_COVERAGE
    }

    /**
     * 从用户粗框出发吸附到槽位方框边缘。
     *
     * @param frame 不含悬浮窗遮挡的游戏画面截图
     * @param input 用户拖拽的轮廓参数
     * @return 通过逐边覆盖率检查的吸附结果；未通过时返回 null
     */
    fun snap(frame: ArgbImage, input: UserFrameInput): SnapResult? =
        measure(frame, input).takeIf { it.passesEdgeCheck }

    /**
     * 执行边缘吸附测量并返回完整结果（无论是否通过逐边覆盖率检查）。
     */
    fun measure(frame: ArgbImage, input: UserFrameInput): SnapResult {
        val lum = Luminance(frame)

        // 第 1 步：搜索等比缩放系数 scale（围绕 input.estimatedScaleX ±5%）与左沿位置 left（±24 px）
        val s0 = input.estimatedScaleX
        var bestV = Double.NEGATIVE_INFINITY
        var scale = s0
        var left = input.left
        for (s in relativeSteps(s0, -SCALE_SEARCH_RANGE, SCALE_SEARCH_RANGE, SCALE_SEARCH_STEP)) {
            for (d in -OFFSET_SEARCH_RANGE..OFFSET_SEARCH_RANGE) {
                val l = input.left + d
                val score = verticalScore(lum, s, l, input.top, SEARCH_SAMPLE_STEP)
                if (score > bestV) {
                    bestV = score
                    scale = s
                    left = l
                }
            }
        }

        // 第 2 步：固定 scale 与 left，仅搜索上沿位置 top（±24 px）
        var bestH = Double.NEGATIVE_INFINITY
        var top = input.top
        for (d in -OFFSET_SEARCH_RANGE..OFFSET_SEARCH_RANGE) {
            val t = input.top + d
            val score = horizontalScore(lum, scale, left, t, SEARCH_SAMPLE_STEP)
            if (score > bestH) {
                bestH = score
                top = t
            }
        }

        // 第 3 步：在 scale（±1%）、left（±2 px）、top（±2 px）上联合精修
        var best = Params(scale, left, top)
        var bestScore = Double.NEGATIVE_INFINITY
        val offsets = halfSteps(REFINE_OFFSET_RANGE, REFINE_OFFSET_STEP)
        for (s in relativeSteps(scale, -REFINE_SCALE_RANGE, REFINE_SCALE_RANGE, REFINE_SCALE_STEP)) {
            for (dl in offsets) {
                for (dt in offsets) {
                    val p = Params(s, left + dl, top + dt)
                    val score = verticalScore(lum, p.scale, p.left, p.top, SEARCH_SAMPLE_STEP) +
                        horizontalScore(lum, p.scale, p.left, p.top, SEARCH_SAMPLE_STEP)
                    if (score > bestScore) {
                        bestScore = score
                        best = p
                    }
                }
            }
        }

        // 第 4 步：逐边覆盖率检查（逐像素采样）
        return SnapResult(best.toCalibration(), edgeCoverages(lum, best))
    }

    /** 搜索参数：等比缩放系数与轮廓左沿、上沿在屏幕上的像素边界坐标。 */
    private class Params(val scale: Double, val left: Double, val top: Double) {
        fun toCalibration(): Calibration = Calibration.uniform(
            scale = scale,
            originX = left - scale * REF_FRAME_LEFT,
            originY = top - scale * REF_FRAME_TOP
        )
    }

    /** 将参考横坐标换算为屏幕边界坐标（以左沿为基准点缩放）。 */
    private fun screenX(refX: Int, scale: Double, left: Double): Double = left + scale * (refX - REF_FRAME_LEFT)

    /** 将参考纵坐标换算为屏幕边界坐标（以上沿为基准点缩放）。 */
    private fun screenY(refY: Int, scale: Double, top: Double): Double = top + scale * (refY - REF_FRAME_TOP)

    /** 计算 8 条竖边的截断梯度之和。 */
    private fun verticalScore(lum: Luminance, scale: Double, left: Double, top: Double, step: Int): Double {
        var total = 0.0
        for (refY in VERTICAL_VALID_Y step step) {
            val y = screenY(refY, scale, top).roundToInt()
            if (y !in 0 until lum.height) continue
            for ((position, polarity) in VERTICAL_EDGES) {
                val b = screenX(position, scale, left).roundToInt()
                total += lum.clampedGradientX(y, b, polarity)
            }
        }
        return total
    }

    /** 计算 8 条横边（各槽位方框的上沿与下沿，避开四角倒角）的截断梯度之和。 */
    private fun horizontalScore(lum: Luminance, scale: Double, left: Double, top: Double, step: Int): Double {
        val topB = screenY(REF_FRAME_TOP, scale, top).roundToInt()
        val bottomB = screenY(REF_FRAME_BOTTOM, scale, top).roundToInt()
        var total = 0.0
        for (box in 0 until ReferenceLayout.SLOT_COUNT) {
            for (refX in horizontalSampleRange(box) step step) {
                val x = screenX(refX, scale, left).roundToInt()
                if (x !in 0 until lum.width) continue
                total += lum.clampedGradientY(x, topB, EdgePolarity.RISING)
                total += lum.clampedGradientY(x, bottomB, EdgePolarity.FALLING)
            }
        }
        return total
    }

    /**
     * 计算 16 条边各自的采样覆盖率，即采样点中方向正确且梯度幅度大于 [EDGE_AMPLITUDE_THRESHOLD] 的比例。
     *
     * 为兼容不同屏幕分辨率与双线性插值造成的边缘亚像素模糊，在法向 `b ± 1` 范围内取最大梯度参与判定；
     * 落在画面外的采样点计入分母并视为未达标。
     */
    private fun edgeCoverages(lum: Luminance, p: Params): List<Double> {
        val result = ArrayList<Double>(16)
        for ((position, polarity) in VERTICAL_EDGES) {
            val b = screenX(position, p.scale, p.left).roundToInt()
            var good = 0
            var total = 0
            for (refY in VERTICAL_VALID_Y) {
                total++
                val y = screenY(refY, p.scale, p.top).roundToInt()
                if (y in 0 until lum.height) {
                    val g = maxOf(
                        lum.clampedGradientX(y, b - 1, polarity),
                        lum.clampedGradientX(y, b, polarity),
                        lum.clampedGradientX(y, b + 1, polarity)
                    )
                    if (g > EDGE_AMPLITUDE_THRESHOLD) good++
                }
            }
            result += good.toDouble() / total
        }
        val topB = screenY(REF_FRAME_TOP, p.scale, p.top).roundToInt()
        val bottomB = screenY(REF_FRAME_BOTTOM, p.scale, p.top).roundToInt()
        for (box in 0 until ReferenceLayout.SLOT_COUNT) {
            for ((b, polarity) in listOf(topB to EdgePolarity.RISING, bottomB to EdgePolarity.FALLING)) {
                var good = 0
                var total = 0
                for (refX in horizontalSampleRange(box)) {
                    total++
                    val x = screenX(refX, p.scale, p.left).roundToInt()
                    if (x in 0 until lum.width) {
                        val g = maxOf(
                            lum.clampedGradientY(x, b - 1, polarity),
                            lum.clampedGradientY(x, b, polarity),
                            lum.clampedGradientY(x, b + 1, polarity)
                        )
                        if (g > EDGE_AMPLITUDE_THRESHOLD) good++
                    }
                }
                result += good.toDouble() / total
            }
        }
        return result
    }

    /** 返回第 [box] 个方框（0~3）横边的有效参考横坐标范围（左右各内缩 [ReferenceLayout.SLOT_FRAME_HORIZONTAL_INSET] 以避开倒角）。 */
    private fun horizontalSampleRange(box: Int): IntRange {
        val inset = ReferenceLayout.SLOT_FRAME_HORIZONTAL_INSET
        return (VERTICAL_EDGES[box * 2].position + inset)..(VERTICAL_EDGES[box * 2 + 1].position - inset)
    }

    /**
     * 整帧图像的灰度亮度缓存（RGB 三通道均值）。
     *
     * 边界 `b` 处的梯度定义为 `(p[b] + p[b+1] - p[b-2] - p[b-1]) / 2`，按极性取符号后截断至 `0..[GRADIENT_CLAMP]`。
     */
    private class Luminance(frame: ArgbImage) {
        val width = frame.width
        val height = frame.height
        private val values = FloatArray(width * height) { i ->
            val p = frame.pixels[i]
            (redOf(p) + greenOf(p) + blueOf(p)) / 3f
        }

        /** 计算第 [y] 行、竖直边界 [b] 处的横向截断梯度；越界时返回 0。 */
        fun clampedGradientX(y: Int, b: Int, polarity: EdgePolarity): Double {
            if (b < 2 || b + 1 >= width) return 0.0
            val i = y * width + b
            return clamp(values[i] + values[i + 1] - values[i - 2] - values[i - 1], polarity)
        }

        /** 计算第 [x] 列、水平边界 [b] 处的纵向截断梯度；越界时返回 0。 */
        fun clampedGradientY(x: Int, b: Int, polarity: EdgePolarity): Double {
            if (b < 2 || b + 1 >= height) return 0.0
            val i = b * width + x
            return clamp(values[i] + values[i + width] - values[i - 2 * width] - values[i - width], polarity)
        }

        private fun clamp(sumDiff: Float, polarity: EdgePolarity): Double {
            val g = sumDiff / 2.0 * (if (polarity == EdgePolarity.RISING) 1 else -1)
            return g.coerceIn(0.0, GRADIENT_CLAMP)
        }
    }

    companion object {
        /** 第 1 槽外框左沿参考横坐标。 */
        val REF_FRAME_LEFT: Int = ReferenceLayout.SLOT_FRAME_VERTICAL_EDGES.first().position

        /** 第 4 槽外框右沿参考横坐标。 */
        val REF_FRAME_RIGHT: Int = ReferenceLayout.SLOT_FRAME_VERTICAL_EDGES.last().position

        /** 槽位外框上沿参考纵坐标。 */
        const val REF_FRAME_TOP: Int = ReferenceLayout.SLOT_FRAME_TOP_Y

        /** 槽位外框下沿参考纵坐标。 */
        const val REF_FRAME_BOTTOM: Int = ReferenceLayout.SLOT_FRAME_BOTTOM_Y

        private val VERTICAL_EDGES = ReferenceLayout.SLOT_FRAME_VERTICAL_EDGES
        private val VERTICAL_VALID_Y = ReferenceLayout.SLOT_FRAME_VERTICAL_VALID_Y

        /** 边缘梯度截断上限，用于抑制槽位内部高对比度图标纹理的干扰。 */
        const val GRADIENT_CLAMP = 8.0

        /** 逐边检查的最低覆盖率要求。 */
        const val MIN_EDGE_COVERAGE = 0.85

        /** 逐边检查中判定单个采样点位于边缘上的最低梯度幅度。 */
        const val EDGE_AMPLITUDE_THRESHOLD = 4.0

        /** 第 1 步等比缩放系数的相对搜索范围（±5%）。 */
        const val SCALE_SEARCH_RANGE = 0.05

        /** 第 1 步等比缩放系数的相对搜索步长（0.5%）。 */
        const val SCALE_SEARCH_STEP = 0.005

        /** 第 1、2 步左沿与上沿位置的搜索半径（±24 px，步长 1 px）。 */
        const val OFFSET_SEARCH_RANGE = 24

        /** 第 3 步缩放系数精修的相对范围（±1%）。 */
        const val REFINE_SCALE_RANGE = 0.01

        /** 第 3 步缩放系数精修的相对步长（0.25%）。 */
        const val REFINE_SCALE_STEP = 0.0025

        /** 第 3 步左沿与上沿位置精修的搜索半径（±2 px）。 */
        const val REFINE_OFFSET_RANGE = 2.0

        /** 第 3 步左沿与上沿位置精修的搜索步长（0.5 px）。 */
        const val REFINE_OFFSET_STEP = 0.5

        /**
         * 第 1~3 步搜索阶段沿边缘的参考像素采样步长。
         *
         * NOTE: 不可继续增大；当缩放系数为 0.5 时，步长 2 已对应屏幕上的逐像素采样。
         */
        const val SEARCH_SAMPLE_STEP = 2

        /** 生成 `center * (1 + from) .. center * (1 + to)` 区间内按相对步长 [step] 递增的序列（包含两端）。 */
        private fun relativeSteps(center: Double, from: Double, to: Double, step: Double): DoubleArray {
            val count = ((to - from) / step + 1e-9).toInt() + 1
            return DoubleArray(count) { center * (1 + from + it * step) }
        }

        /** 生成 `-range..range` 区间内按步长 [step] 递增的序列（包含两端）。 */
        private fun halfSteps(range: Double, step: Double): DoubleArray {
            val count = (2 * range / step + 1e-9).toInt() + 1
            return DoubleArray(count) { -range + it * step }
        }
    }
}
