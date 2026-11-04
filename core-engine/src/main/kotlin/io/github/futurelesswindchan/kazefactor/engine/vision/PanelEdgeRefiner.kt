package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.blueOf
import io.github.futurelesswindchan.kazefactor.engine.image.greenOf
import io.github.futurelesswindchan.kazefactor.engine.image.redOf
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 面板边框精修（架构方案第 3.1 节第 2 段）。
 *
 * 在帮助图标粗定位给出的 `s0`、`ox0` 基础上，用面板左右竖条的 4 条竖边求出准确的缩放和 X 偏移，
 * 再用槽位外框上下沿和面板下边框求 Y 偏移。自动校准只求一个缩放，横纵共用；
 * 纵向残差同时检验了「横竖缩放一致」（236 到 690 相距 454 px，缩放差 1% 就有约 4.5 px 残差）。
 *
 * 找边只看「哪里跳变」，不看跳变后的绝对亮度，所以对设备之间的亮度、对比度差异不敏感。
 * 所有位置都是像素边界坐标，与 [ReferenceLayout] 和 [Calibration] 的约定一致。
 *
 * NOTE: 本类不可变，可在多个协程间共享。
 */
class PanelEdgeRefiner {

    /**
     * 精修结果。
     *
     * @property calibration 精修后的换算（横纵缩放相同）
     * @property horizontalResidual 4 条竖边最小二乘拟合的最大残差（px）
     * @property verticalResidual 3 条横边相对平均 Y 偏移的最大残差（px）
     * @property source 精修所依据的帮助图标候选，调试和测试用
     */
    data class RefineResult(
        val calibration: Calibration,
        val horizontalResidual: Double,
        val verticalResidual: Double,
        val source: HelpIconEstimate
    ) {
        /** 两个方向的残差都不超过 [MAX_RESIDUAL]（第 3.1 节第 2 段第 4 条）。 */
        val passesResidualCheck: Boolean
            get() = horizontalResidual <= MAX_RESIDUAL && verticalResidual <= MAX_RESIDUAL
    }

    /**
     * 以帮助图标候选 [estimate] 为起点精修。
     *
     * 本函数只负责测量，不做残差判定：残差超标的结果也会返回，交给调用方看
     * [RefineResult.passesResidualCheck]，这样测试报告能看到假锚点被拒的具体原因。
     *
     * @param frame 完整截图
     * @param estimate 粗定位的一个候选
     * @return 精修结果；有边落在画面外、窗口内找不到符号正确的跳变，或拟合出非正缩放时返回 null
     */
    fun refine(frame: ArgbImage, estimate: HelpIconEstimate): RefineResult? {
        val s0 = estimate.scale
        val ox0 = estimate.originX

        // 第 1 步：横向。4 条竖边对 x_屏 = ox + s × x_参 做最小二乘
        val rowProfile = horizontalProfile(frame, estimate)
        val verticalEdges = ReferenceLayout.PANEL_VERTICAL_EDGES
        val xs = verticalEdges.map { edge ->
            val predicted = ox0 + s0 * edge.position
            findEdge(rowProfile, predicted, horizontalWindow(s0, edge.position), edge.polarity) ?: return null
        }
        val fit = fitLine(verticalEdges.map { it.position.toDouble() }, xs) ?: return null
        val scale = fit.slope
        if (scale <= 0) return null
        val originX = fit.intercept

        // 第 2 步：纵向。s 已知，按 oy_pred = 图标 y − s × 125 预测 3 条横边，oy 取 3 个点的平均
        val oyPred = estimate.y - scale * ReferenceLayout.HELP_ICON_RECT.y
        val columnProfile = verticalProfile(frame, scale, originX)
        val horizontalEdges = ReferenceLayout.PANEL_HORIZONTAL_EDGES
        val ys = horizontalEdges.map { edge ->
            val predicted = oyPred + scale * edge.position
            findEdge(columnProfile, predicted, VERTICAL_WINDOW, edge.polarity) ?: return null
        }
        val offsets = horizontalEdges.zip(ys) { edge, y -> y - scale * edge.position }
        val originY = offsets.average()
        val verticalResidual = offsets.maxOf { abs(it - originY) }

        return RefineResult(
            calibration = Calibration.uniform(scale, originX, originY),
            horizontalResidual = fit.maxResidual,
            verticalResidual = verticalResidual,
            source = estimate
        )
    }

    /**
     * 横向亮度剖面：参考 y 200..660 每 4 行取 1 行（跳过刷新按钮所在的 370..505），
     * 按粗定位换算成屏幕行后逐列求平均亮度。落在画面外的行跳过。
     *
     * 行位置以帮助图标为基准换算（`图标 y + s0 × (参考 y − 125)`），离图标越远，`s0` 误差带来的偏移越大，
     * 最远约 0.06 × 535 ≈ 32 px，仍在面板竖条的纵向范围内。
     */
    private fun horizontalProfile(frame: ArgbImage, estimate: HelpIconEstimate): DoubleArray {
        val profile = DoubleArray(frame.width)
        var rows = 0
        for (refY in ReferenceLayout.HORIZONTAL_PROFILE_ROWS) {
            if (refY in ReferenceLayout.HORIZONTAL_PROFILE_EXCLUDED_ROWS) continue
            val y = (estimate.originY + estimate.scale * refY).roundToInt()
            if (y !in 0 until frame.height) continue
            val base = y * frame.width
            for (x in 0 until frame.width) profile[x] += luminance(frame.pixels[base + x])
            rows++
        }
        if (rows > 0) for (x in profile.indices) profile[x] /= rows
        return profile
    }

    /**
     * 纵向亮度剖面：架构方案第 3.1 节表中的 6 组列，按已精修的横向换算取屏幕列，逐行求平均亮度。
     *
     * 缩小时几个参考列可能落到同一屏幕列上，这里不去重：权重只影响平均值，不影响跳变位置。
     */
    private fun verticalProfile(frame: ArgbImage, scale: Double, originX: Double): DoubleArray {
        val profile = DoubleArray(frame.height)
        var columns = 0
        for (range in ReferenceLayout.VERTICAL_PROFILE_COLUMNS) {
            for (refX in range) {
                // 取参考像素中心对应的屏幕像素
                val x = (originX + scale * (refX + 0.5) - 0.5).roundToInt()
                if (x !in 0 until frame.width) continue
                for (y in 0 until frame.height) profile[y] += luminance(frame.pixels[y * frame.width + x])
                columns++
            }
        }
        if (columns > 0) for (y in profile.indices) profile[y] /= columns
        return profile
    }

    /**
     * 在 `predicted ± window` 内找符号与 [polarity] 一致、幅度最大的跳变，抛物线插值到亚像素。
     *
     * 边界 b 处的梯度取「b 之后 2 px 之和 − b 之前 2 px 之和」的一半：
     * `g(b) = (p[b] + p[b+1] − p[b−1] − p[b−2]) / 2`。理想阶跃在 b 处取最大值，
     * 两侧各降一半，正好对称，插值结果就是 b 本身。
     *
     * @return 边界坐标（可为小数）；窗口与剖面没有交集，或窗口内没有符号正确的梯度时返回 null
     */
    private fun findEdge(profile: DoubleArray, predicted: Double, window: Double, polarity: EdgePolarity): Double? {
        val sign = if (polarity == EdgePolarity.RISING) 1.0 else -1.0
        // g(b) 需要 p[b−2]..p[b+1]，插值还要 g(b±1)，所以 b 的合法范围是 3..size−3
        val lo = maxOf(3, kotlin.math.ceil(predicted - window).toInt())
        val hi = minOf(profile.size - 3, kotlin.math.floor(predicted + window).toInt())
        if (lo > hi) return null

        var best = -1
        var bestValue = 0.0
        for (b in lo..hi) {
            val g = sign * gradient(profile, b)
            if (g > bestValue) {
                bestValue = g
                best = b
            }
        }
        if (best < 0) return null

        val left = sign * gradient(profile, best - 1)
        val right = sign * gradient(profile, best + 1)
        val denominator = left - 2 * bestValue + right
        val delta = if (denominator < 0) (left - right) / (2 * denominator) else 0.0
        return best + delta.coerceIn(-0.5, 0.5)
    }

    private fun gradient(p: DoubleArray, b: Int): Double = (p[b] + p[b + 1] - p[b - 1] - p[b - 2]) / 2

    /** 直线拟合结果 `y = intercept + slope × x`。 */
    private class LineFit(val slope: Double, val intercept: Double, val maxResidual: Double)

    /** 最小二乘拟合 `y = a + b × x`；x 全部相同时返回 null。 */
    private fun fitLine(xs: List<Double>, ys: List<Double>): LineFit? {
        val n = xs.size
        val meanX = xs.average()
        val meanY = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sxx += (xs[i] - meanX) * (xs[i] - meanX)
            sxy += (xs[i] - meanX) * (ys[i] - meanY)
        }
        if (sxx == 0.0) return null
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        val maxResidual = (0 until n).maxOf { abs(ys[it] - (intercept + slope * xs[it])) }
        return LineFit(slope, intercept, maxResidual)
    }

    companion object {
        /** 横向、纵向拟合残差的上限（px）。 */
        const val MAX_RESIDUAL = 1.5

        /**
         * 竖边搜索窗口的系数。窗口为 `系数 × s0 × |参考 x − 1475| + 6`，
         * 2026-11-02 由 0.04 改为 0.06，与粗定位 `s0` 误差最多 6% 对应。
         */
        const val WINDOW_COEFFICIENT = 0.06

        /** 竖边搜索窗口的固定余量（px），覆盖帮助图标 ±2 px 的位置误差和取整。 */
        const val WINDOW_MARGIN = 6.0

        /** 横边搜索窗口（px）。横向缩放已经精修过，只剩帮助图标纵向位置的误差。 */
        const val VERTICAL_WINDOW = 8.0

        /** 竖边的搜索半宽。 */
        private fun horizontalWindow(s0: Double, refX: Int): Double =
            WINDOW_COEFFICIENT * s0 * abs(refX - ReferenceLayout.HELP_ICON_RECT.x) + WINDOW_MARGIN

        /** 亮度取 RGB 平均，与调研报告实验 8 的剖面一致。 */
        private fun luminance(argb: Int): Double = (redOf(argb) + greenOf(argb) + blueOf(argb)) / 3.0
    }
}
