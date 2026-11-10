package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.blueOf
import io.github.futurelesswindchan.kazefactor.engine.image.greenOf
import io.github.futurelesswindchan.kazefactor.engine.image.redOf
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 面板边框精修器。
 *
 * 在帮助图标粗定位给出的初始缩放 `s0` 与横向偏移 `ox0` 基础上，利用面板左右竖条的 4 条竖边
 * 执行最小二乘拟合求出准确的等比缩放系数与横向偏移，再利用槽位外框上下沿和面板下边框求出纵向偏移。
 *
 * 边缘定位基于亮度跳变梯度而非绝对亮度阈值，对不同设备的亮度与对比度差异不敏感。
 * 所有坐标均为像素边界坐标，与 [ReferenceLayout] 和 [Calibration] 的几何约定一致。
 *
 * NOTE: 本类无可变状态，可在多个协程间安全共享。
 */
class PanelEdgeRefiner {

    /**
     * 面板边框精修结果。
     *
     * @property calibration 精修后的等比换算参数（`scaleX == scaleY`）
     * @property horizontalResidual 4 条竖边最小二乘拟合的最大残差（单位：px）
     * @property verticalResidual 3 条横边相对平均纵向偏移的最大残差（单位：px）
     * @property source 精修所依据的帮助图标粗定位候选
     */
    data class RefineResult(
        val calibration: Calibration,
        val horizontalResidual: Double,
        val verticalResidual: Double,
        val source: HelpIconEstimate
    ) {
        /**
         * 随缩放系数动态放宽的最大允许残差阈值（单位：px）。
         *
         * 高分辨率屏幕（`scale > 1.0`）经过 GPU 重采样插值后边缘过渡带展宽，
         * 按 `maxOf(2.5, 2.5 * scaleX)` 动态计算容差以避免高分屏被过严的固定阈值误拒。
         */
        val maxAllowedResidual: Double = maxOf(MAX_RESIDUAL, MAX_RESIDUAL * calibration.scaleX)

        /** 横向与纵向拟合残差是否均在 [maxAllowedResidual] 允许范围内。 */
        val passesResidualCheck: Boolean
            get() = horizontalResidual <= maxAllowedResidual && verticalResidual <= maxAllowedResidual
    }

    /**
     * 以帮助图标候选 [estimate] 为起点执行面板边框精修。
     *
     * 本方法仅负责边缘测量与直线拟合，不直接过滤残差超标项；调用方通过 [RefineResult.passesResidualCheck]
     * 判断残差是否达标，以便诊断与测试报告记录候选被拒的具体原因。
     *
     * @param frame 完整屏幕截图
     * @param estimate 帮助图标粗定位候选
     * @return 精修结果；当预测边落在画面外、搜索窗口内无同极性跳变或拟合出非正缩放系数时返回 null
     */
    fun refine(frame: ArgbImage, estimate: HelpIconEstimate): RefineResult? {
        val s0 = estimate.scale
        val ox0 = estimate.originX

        // 第 1 步：横向拟合。对 4 条竖边的屏幕坐标与参考坐标执行最小二乘线性回归：x_screen = ox + s * x_ref
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

        // 第 2 步：纵向拟合。固定精修后的等比缩放 scale，根据帮助图标纵坐标预测 3 条横边并取均值作为 originY
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
     * 计算横向亮度剖面：在参考纵坐标 `200..660` 范围内每隔 4 行采样 1 行（跳过刷新按钮所在的 `370..505`），
     * 按粗定位参数换算为屏幕行后逐列求平均亮度。超出画面边界的采样行自动跳过。
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
     * 计算纵向亮度剖面：按已精修的横向换算参数将 [ReferenceLayout.VERTICAL_PROFILE_COLUMNS] 映射为屏幕列，
     * 逐行计算平均亮度。
     */
    private fun verticalProfile(frame: ArgbImage, scale: Double, originX: Double): DoubleArray {
        val profile = DoubleArray(frame.height)
        var columns = 0
        for (range in ReferenceLayout.VERTICAL_PROFILE_COLUMNS) {
            for (refX in range) {
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
     * 在 `predicted ± window` 范围内搜索极性与 [polarity] 一致且梯度幅值最大的位置，并通过抛物线插值精确到亚像素。
     *
     * 边界 `b` 处的梯度定义为 `g(b) = (p[b] + p[b+1] - p[b-1] - p[b-2]) / 2`。
     *
     * @return 亚像素边界坐标；当搜索窗口越界或窗口内不存在同极性梯度时返回 null
     */
    private fun findEdge(profile: DoubleArray, predicted: Double, window: Double, polarity: EdgePolarity): Double? {
        val sign = if (polarity == EdgePolarity.RISING) 1.0 else -1.0
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

    /** 最小二乘直线拟合结果 `y = intercept + slope * x`。 */
    private class LineFit(val slope: Double, val intercept: Double, val maxResidual: Double)

    /** 对给定点集执行最小二乘线性拟合 `y = a + b * x`；自变量方差为 0 时返回 null。 */
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
        /** 基准分辨率（`scale <= 1.0`）下横向与纵向拟合残差的基础上限（单位：px）。 */
        const val MAX_RESIDUAL = 2.5

        /** 竖边搜索窗口相对帮助图标距离的比例系数（对应粗定位 `s0` 最大 6% 误差）。 */
        const val WINDOW_COEFFICIENT = 0.06

        /** 竖边搜索窗口的固定像素余量（单位：px），用于覆盖帮助图标位置离散误差。 */
        const val WINDOW_MARGIN = 6.0

        /** 横边搜索窗口半宽（单位：px）。 */
        const val VERTICAL_WINDOW = 8.0

        /** 计算参考横坐标 [refX] 处竖边的搜索窗口半宽。 */
        private fun horizontalWindow(s0: Double, refX: Int): Double =
            WINDOW_COEFFICIENT * s0 * abs(refX - ReferenceLayout.HELP_ICON_RECT.x) + WINDOW_MARGIN

        /** 计算像素的 RGB 三通道均值亮度。 */
        private fun luminance(argb: Int): Double = (redOf(argb) + greenOf(argb) + blueOf(argb)) / 3.0
    }
}
