package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.blueOf
import io.github.futurelesswindchan.kazefactor.engine.image.greenOf
import io.github.futurelesswindchan.kazefactor.engine.image.redOf
import kotlin.math.roundToInt

/**
 * 手动引导时用户拖好的 4 格轮廓（架构方案第 3.4.3 节），屏幕坐标，均为像素边界。
 *
 * 用户只拉轮廓的左右两端，高度跟着宽度按参考比例变化，所以只需要左、右、上三个值。
 * 「左」「右」对应第 1 槽外框左沿（参考 x 1076）和第 4 槽外框右沿（参考 x 1680），
 * 「上」对应外框上沿（参考 y 236）。
 *
 * @property left 轮廓左沿
 * @property right 轮廓右沿，必须大于 [left]
 * @property top 轮廓上沿
 * @throws IllegalArgumentException [right] 不大于 [left] 时
 */
data class UserFrameInput(val left: Double, val right: Double, val top: Double) {
    init {
        require(right > left) { "轮廓右沿必须在左沿右侧：left = $left，right = $right" }
    }

    /** 由轮廓宽度推算的横向缩放初值。 */
    val estimatedScaleX: Double
        get() = (right - left) / (Snapper.REF_FRAME_RIGHT - Snapper.REF_FRAME_LEFT)

    companion object {
        /**
         * 按 [calibration] 画出的轮廓，用于手动引导的初始位置（按屏幕高度等比换算的参考位置）
         * 和测试里构造「真值 + 误差」的输入。
         */
        fun fromCalibration(calibration: Calibration): UserFrameInput = UserFrameInput(
            left = calibration.toScreenX(Snapper.REF_FRAME_LEFT.toDouble()),
            right = calibration.toScreenX(Snapper.REF_FRAME_RIGHT.toDouble()),
            top = calibration.toScreenY(Snapper.REF_FRAME_TOP.toDouble())
        )
    }
}

/**
 * 手动引导的吸附：用户粗框 → 槽位方框边缘 → [Calibration]（架构方案第 3.4.4 节）。
 *
 * 只假设「4 个等大、等间距的方框」，不使用帮助图标和面板边框，所以面板换了样式也能用；
 * 横纵缩放分开求，面板被横向拉伸也能吸准。
 *
 * 步骤：
 * 1. 竖边搜索：只看 8 条竖边，搜 `scaleX`（初值 ±20%，步长 0.5%）和左沿位置（±40 px，步长 1）。
 * 2. 横边搜索：固定横向，只看 8 条横边，搜 `scaleY`（`scaleX` × 0.75..1.33，步长 0.5%）和上沿位置（±40 px，步长 1）。
 * 3. 联合精修：16 条边一起，两个缩放各 ±1%（步长 0.25%），左沿、上沿各 ±2 px（步长 0.5）。
 * 4. 逐边检查：16 条边各自的覆盖率都必须 ≥ [MIN_EDGE_COVERAGE]，否则吸附失败。
 *
 * 搜索参数用「左沿、上沿在屏幕上的位置」而不是 `origin`：缩放以左沿、上沿为基点变化，
 * 换缩放时轮廓不会整体漂走，±40 px 的窗口始终围着用户拖的位置。
 *
 * 梯度截断到 ±[GRADIENT_CLAMP] 是关键：图标内部的边缘对比度高达 100 以上，不截断时会主导得分，
 * 把轮廓吸到图标上。方框边缘的跳变约 18，截断后每个采样点贡献相同，
 * 得分就变成「有多少采样点落在正确的边上」（调研报告实验 11 第 4、5 轮）。
 *
 * ```
 * val result = Snapper().snap(cleanScreenshot, UserFrameInput(left, right, top))
 * if (result == null) showRedOutline() // 「没对准，请把框拖到 4 个方格上再试」
 * else showPreview(result.calibration)
 * ```
 *
 * NOTE: 本类不可变，可在多个协程间共享。耗时没有在手机上测过（实验 11 的原型单帧 1~3 秒，未优化）。
 */
class Snapper {

    /**
     * 吸附结果。
     *
     * @property calibration 吸附得到的换算，横纵缩放可以不同
     * @property edgeCoverages 16 条边各自的覆盖率：前 8 个是竖边（按 x 排列），后 8 个是横边（每个方框上沿、下沿）
     */
    data class SnapResult(val calibration: Calibration, val edgeCoverages: List<Double>) {
        /** 最差那条边的覆盖率。 */
        val worstCoverage: Double get() = edgeCoverages.minOrNull() ?: 0.0

        /** 每条边都达到 [MIN_EDGE_COVERAGE]。 */
        val passesEdgeCheck: Boolean get() = worstCoverage >= MIN_EDGE_COVERAGE
    }

    /**
     * 从用户粗框出发吸附到槽位方框边缘。
     *
     * @param frame 手动引导时截下的干净画面（不带悬浮窗）
     * @param input 用户拖好的轮廓
     * @return 通过逐边检查的结果；不通过时返回 null，提示用户重新对准
     */
    fun snap(frame: ArgbImage, input: UserFrameInput): SnapResult? =
        measure(frame, input).takeIf { it.passesEdgeCheck }

    /**
     * 与 [snap] 相同，但逐边检查不通过时也返回结果，供测试报告查看被拒的原因。
     */
    fun measure(frame: ArgbImage, input: UserFrameInput): SnapResult {
        val lum = Luminance(frame)

        // 第 1 步：竖边。纵向还不知道，采样行按 scaleY = scaleX 粗估（见 verticalScore）
        val sx0 = input.estimatedScaleX
        var bestV = Double.NEGATIVE_INFINITY
        var sx = sx0
        var left = input.left
        for (s in relativeSteps(sx0, -SCALE_SEARCH_RANGE, SCALE_SEARCH_RANGE, SCALE_SEARCH_STEP)) {
            for (d in -OFFSET_SEARCH_RANGE..OFFSET_SEARCH_RANGE) {
                val l = input.left + d
                val score = verticalScore(lum, s, l, s, input.top, SEARCH_SAMPLE_STEP)
                if (score > bestV) {
                    bestV = score
                    sx = s
                    left = l
                }
            }
        }

        // 第 2 步：横边。固定 scaleX 和左沿
        var bestH = Double.NEGATIVE_INFINITY
        var sy = sx
        var top = input.top
        for (s in relativeSteps(sx, SCALE_Y_RATIO_MIN - 1, SCALE_Y_RATIO_MAX - 1, SCALE_SEARCH_STEP)) {
            for (d in -OFFSET_SEARCH_RANGE..OFFSET_SEARCH_RANGE) {
                val t = input.top + d
                val score = horizontalScore(lum, sx, left, s, t, SEARCH_SAMPLE_STEP)
                if (score > bestH) {
                    bestH = score
                    sy = s
                    top = t
                }
            }
        }

        // 第 3 步：联合精修
        var best = Params(sx, left, sy, top)
        var bestScore = Double.NEGATIVE_INFINITY
        val offsets = halfSteps(REFINE_OFFSET_RANGE, REFINE_OFFSET_STEP)
        for (s1 in relativeSteps(sx, -REFINE_SCALE_RANGE, REFINE_SCALE_RANGE, REFINE_SCALE_STEP)) {
            for (s2 in relativeSteps(sy, -REFINE_SCALE_RANGE, REFINE_SCALE_RANGE, REFINE_SCALE_STEP)) {
                for (dl in offsets) {
                    for (dt in offsets) {
                        val p = Params(s1, left + dl, s2, top + dt)
                        val score = verticalScore(lum, p.scaleX, p.left, p.scaleY, p.top, SEARCH_SAMPLE_STEP) +
                            horizontalScore(lum, p.scaleX, p.left, p.scaleY, p.top, SEARCH_SAMPLE_STEP)
                        if (score > bestScore) {
                            bestScore = score
                            best = p
                        }
                    }
                }
            }
        }

        // 第 4 步：逐边检查（逐像素采样）
        return SnapResult(best.toCalibration(), edgeCoverages(lum, best))
    }

    /** 搜索参数：缩放 + 轮廓左沿、上沿在屏幕上的位置（像素边界）。 */
    private class Params(val scaleX: Double, val left: Double, val scaleY: Double, val top: Double) {
        fun toCalibration(): Calibration = Calibration(
            scaleX, scaleY,
            originX = left - scaleX * REF_FRAME_LEFT,
            originY = top - scaleY * REF_FRAME_TOP
        )
    }

    /** 参考 x 换算成屏幕边界坐标：以左沿为基点缩放。 */
    private fun screenX(refX: Int, scaleX: Double, left: Double): Double = left + scaleX * (refX - REF_FRAME_LEFT)

    /** 参考 y 换算成屏幕边界坐标：以上沿为基点缩放。 */
    private fun screenY(refY: Int, scaleY: Double, top: Double): Double = top + scaleY * (refY - REF_FRAME_TOP)

    /**
     * 8 条竖边的截断梯度之和。
     *
     * 第 1 步还不知道 `scaleY`，调用方传入 `scaleY = scaleX` 粗估采样行。采样行只决定在哪些行上取剖面，
     * 竖边的横坐标只取决于横向参数；行偏几个像素时大部分采样点仍落在竖边的有效范围内。
     */
    private fun verticalScore(lum: Luminance, sx: Double, left: Double, sy: Double, top: Double, step: Int): Double {
        var total = 0.0
        for (refY in VERTICAL_VALID_Y step step) {
            val y = screenY(refY, sy, top).roundToInt()
            if (y !in 0 until lum.height) continue
            for (edge in VERTICAL_EDGES) {
                val b = screenX(edge.position, sx, left).roundToInt()
                total += lum.clampedGradientX(y, b, edge.polarity)
            }
        }
        return total
    }

    /** 8 条横边（每个方框的上沿、下沿，避开倒角）的截断梯度之和。 */
    private fun horizontalScore(lum: Luminance, sx: Double, left: Double, sy: Double, top: Double, step: Int): Double {
        val topB = screenY(REF_FRAME_TOP, sy, top).roundToInt()
        val bottomB = screenY(REF_FRAME_BOTTOM, sy, top).roundToInt()
        var total = 0.0
        for (box in 0 until ReferenceLayout.SLOT_COUNT) {
            for (refX in horizontalSampleRange(box) step step) {
                val x = screenX(refX, sx, left).roundToInt()
                if (x !in 0 until lum.width) continue
                total += lum.clampedGradientY(x, topB, EdgePolarity.RISING)
                total += lum.clampedGradientY(x, bottomB, EdgePolarity.FALLING)
            }
        }
        return total
    }

    /**
     * 16 条边各自的覆盖率：采样点里「符号正确且幅度 > [EDGE_AMPLITUDE_THRESHOLD]」的占比。
     * 落在画面外的采样点计入分母，算作不通过：边伸出画面说明轮廓位置不对。
     */
    private fun edgeCoverages(lum: Luminance, p: Params): List<Double> {
        val result = ArrayList<Double>(16)
        for (edge in VERTICAL_EDGES) {
            val b = screenX(edge.position, p.scaleX, p.left).roundToInt()
            var good = 0
            var total = 0
            for (refY in VERTICAL_VALID_Y) {
                total++
                val y = screenY(refY, p.scaleY, p.top).roundToInt()
                if (y in 0 until lum.height && lum.clampedGradientX(y, b, edge.polarity) > EDGE_AMPLITUDE_THRESHOLD) good++
            }
            result += good.toDouble() / total
        }
        val topB = screenY(REF_FRAME_TOP, p.scaleY, p.top).roundToInt()
        val bottomB = screenY(REF_FRAME_BOTTOM, p.scaleY, p.top).roundToInt()
        for (box in 0 until ReferenceLayout.SLOT_COUNT) {
            for ((b, polarity) in listOf(topB to EdgePolarity.RISING, bottomB to EdgePolarity.FALLING)) {
                var good = 0
                var total = 0
                for (refX in horizontalSampleRange(box)) {
                    total++
                    val x = screenX(refX, p.scaleX, p.left).roundToInt()
                    if (x in 0 until lum.width && lum.clampedGradientY(x, b, polarity) > EDGE_AMPLITUDE_THRESHOLD) good++
                }
                result += good.toDouble() / total
            }
        }
        return result
    }

    /** 第 [box] 个方框（0~3）横边的采样列：(左沿 + 9)..(右沿 − 9)（参考坐标，两端都含，与架构方案第 3.4.4 节一致）。 */
    private fun horizontalSampleRange(box: Int): IntRange {
        val inset = ReferenceLayout.SLOT_FRAME_HORIZONTAL_INSET
        return (VERTICAL_EDGES[box * 2].position + inset)..(VERTICAL_EDGES[box * 2 + 1].position - inset)
    }

    /**
     * 整帧的亮度（RGB 平均），每次吸附只算一次，搜索时不再重复解像素。
     *
     * 梯度：边界 b 处 `(p[b] + p[b+1] − p[b−2] − p[b−1]) / 2`，按极性取符号后截断到 0..[GRADIENT_CLAMP]。
     * 符号相反的跳变记 0，不扣分。
     */
    private class Luminance(frame: ArgbImage) {
        val width = frame.width
        val height = frame.height
        private val values = FloatArray(width * height) { i ->
            val p = frame.pixels[i]
            (redOf(p) + greenOf(p) + blueOf(p)) / 3f
        }

        /** 第 [y] 行、竖直边界 [b] 处的横向梯度；越界时为 0。 */
        fun clampedGradientX(y: Int, b: Int, polarity: EdgePolarity): Double {
            if (b < 2 || b + 1 >= width) return 0.0
            val i = y * width + b
            return clamp(values[i] + values[i + 1] - values[i - 2] - values[i - 1], polarity)
        }

        /** 第 [x] 列、水平边界 [b] 处的纵向梯度；越界时为 0。 */
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
        /** 第 1 槽外框左沿（参考坐标），轮廓的左端。 */
        val REF_FRAME_LEFT: Int = ReferenceLayout.SLOT_FRAME_VERTICAL_EDGES.first().position

        /** 第 4 槽外框右沿，轮廓的右端。 */
        val REF_FRAME_RIGHT: Int = ReferenceLayout.SLOT_FRAME_VERTICAL_EDGES.last().position

        /** 外框上沿，轮廓的上端。 */
        const val REF_FRAME_TOP: Int = ReferenceLayout.SLOT_FRAME_TOP_Y

        /** 外框下沿。 */
        const val REF_FRAME_BOTTOM: Int = ReferenceLayout.SLOT_FRAME_BOTTOM_Y

        private val VERTICAL_EDGES = ReferenceLayout.SLOT_FRAME_VERTICAL_EDGES
        private val VERTICAL_VALID_Y = ReferenceLayout.SLOT_FRAME_VERTICAL_VALID_Y

        /** 梯度截断值。方框边缘跳变约 18，图标内部边缘可达 100 以上。 */
        const val GRADIENT_CLAMP = 8.0

        /** 逐边检查的覆盖率下限。实验 11：正确吸附的帧最差那条边 ≥ 0.98，错误吸附的 ≤ 0.83。 */
        const val MIN_EDGE_COVERAGE = 0.9

        /** 逐边检查中一个采样点算「落在边上」的最低幅度。 */
        const val EDGE_AMPLITUDE_THRESHOLD = 4.0

        /**
         * 第 1 步 `scaleX` 的搜索范围（相对初值 ±20%）。
         *
         * 2026-11-04 由 ±12% 放宽：缩放 0.75、用户框每边内缩 30 px 时框比真值窄约 13%，超出 ±12% 会被拒收。
         * ±20% 覆盖缩放 0.5 时每边 30 px 的误差（架构方案第 3.4.4 节、调研报告实验 13）。
         */
        const val SCALE_SEARCH_RANGE = 0.20

        /** 第 1、2 步缩放的步长（相对值 0.5%）。 */
        const val SCALE_SEARCH_STEP = 0.005

        /** 第 1、2 步左沿、上沿的搜索范围（±40 px，步长 1）。 */
        const val OFFSET_SEARCH_RANGE = 40

        /** 第 2 步 `scaleY / scaleX` 的下限。 */
        const val SCALE_Y_RATIO_MIN = 0.75

        /** 第 2 步 `scaleY / scaleX` 的上限。 */
        const val SCALE_Y_RATIO_MAX = 1.33

        /** 第 3 步缩放精修范围（±1%）。 */
        const val REFINE_SCALE_RANGE = 0.01

        /** 第 3 步缩放精修步长（0.25%）。 */
        const val REFINE_SCALE_STEP = 0.0025

        /** 第 3 步左沿、上沿精修范围（±2 px）。 */
        const val REFINE_OFFSET_RANGE = 2.0

        /** 第 3 步左沿、上沿精修步长（0.5 px）。 */
        const val REFINE_OFFSET_STEP = 0.5

        /**
         * 第 1~3 步搜索时每隔几个参考像素采样一次（架构方案第 3.4.4 节的规定）。
         *
         * 搜索只比较得分高低，减半采样不影响排名；第 4 步逐边检查仍逐像素采样，验收以它为准。
         *
         * XXX: 不要再加大。缩放 0.5 时隔 1 取 1 已经相当于屏幕上每个像素都采一次，再大就会漏掉边。
         */
        const val SEARCH_SAMPLE_STEP = 2

        /** `center × (1 + from) .. center × (1 + to)`，按相对步长 [step] 生成，两端都包含。 */
        private fun relativeSteps(center: Double, from: Double, to: Double, step: Double): DoubleArray {
            val count = ((to - from) / step + 1e-9).toInt() + 1
            return DoubleArray(count) { center * (1 + from + it * step) }
        }

        /** −range..range，步长 [step]。 */
        private fun halfSteps(range: Double, step: Double): DoubleArray {
            val count = (2 * range / step + 1e-9).toInt() + 1
            return DoubleArray(count) { -range + it * step }
        }
    }
}
