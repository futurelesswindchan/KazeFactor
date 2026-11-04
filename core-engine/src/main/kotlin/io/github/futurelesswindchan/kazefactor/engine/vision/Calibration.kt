package io.github.futurelesswindchan.kazefactor.engine.vision

import kotlin.math.roundToInt

/**
 * 参考截图（2388×1080）坐标到当前屏幕坐标的换算：`屏幕 = origin + scale × 参考`，横纵分开。
 *
 * 坐标按「像素边界」理解（架构方案第 3.1 节）：像素下标 i 的左边界是 i，右边界是 i + 1。
 * 换算公式只对边界成立，所以矩形要先换算左右（上下）两条边界，再取整得到像素矩形，
 * 不能用「左上角 + 宽 × scale」，否则取整误差会在宽度上再叠一次。
 *
 * 自动校准（`Calibrator`）只求一个缩放，结果同时填进 [scaleX]、[scaleY]，用 [uniform] 创建；
 * 手动吸附（第 3c 步的 `Snapper`）可以得到不等的两个值。
 *
 * ```
 * val cal = Calibration.uniform(scale = 0.5, originX = 10.0, originY = 20.0)
 * cal.toScreenRect(ReferenceLayout.slotRect(1)) // PixelRect(554, 144, 45, 45)
 * ```
 *
 * @property scaleX 横向缩放，必须为正的有限值
 * @property scaleY 纵向缩放，必须为正的有限值
 * @property originX 参考坐标 0 对应的屏幕横坐标（边界坐标，可为负或小数）
 * @property originY 参考坐标 0 对应的屏幕纵坐标
 * @throws IllegalArgumentException 缩放非正、为 NaN 或无穷，或偏移不是有限值时
 */
data class Calibration(
    val scaleX: Double,
    val scaleY: Double,
    val originX: Double,
    val originY: Double
) {
    init {
        require(scaleX.isFinite() && scaleX > 0) { "scaleX 必须为正的有限值：$scaleX" }
        require(scaleY.isFinite() && scaleY > 0) { "scaleY 必须为正的有限值：$scaleY" }
        require(originX.isFinite() && originY.isFinite()) { "偏移必须为有限值：($originX, $originY)" }
    }

    /** 参考横坐标（边界）换算成屏幕横坐标（边界），不取整。 */
    fun toScreenX(refX: Double): Double = originX + scaleX * refX

    /** 参考纵坐标（边界）换算成屏幕纵坐标（边界），不取整。 */
    fun toScreenY(refY: Double): Double = originY + scaleY * refY

    /**
     * 屏幕横坐标（边界）反算回参考坐标，[toScreenX] 的逆运算。
     *
     * 手动引导时用户点下的刷新按钮位置要按这个换算存进位置档案（架构方案第 3.4.1 节）。
     */
    fun toReferenceX(screenX: Double): Double = (screenX - originX) / scaleX

    /** 屏幕纵坐标反算回参考坐标，[toScreenY] 的逆运算。 */
    fun toReferenceY(screenY: Double): Double = (screenY - originY) / scaleY

    /**
     * 把参考截图上的像素矩形换算成屏幕上的像素矩形。
     *
     * 四条边界各自换算后四舍五入（`roundToInt`，.5 向正方向进位），宽高由取整后的边界相减得到。
     * 因此相邻矩形换算后仍然首尾相接，不会因取整多出或少掉 1 px。
     *
     * NOTE: 换算后的边长可能和 `round(参考边长 × scale)` 差 1 px。例如 scale = 1.25 时
     * 90 px 的槽位可能变成 112 或 113 px，取决于槽位落在哪里。需要固定边长的调用方
     * （如按 [TemplateBank.slotWidth]、[TemplateBank.slotHeight] 裁搜索区域）应自行以换算后的左上角为准取固定边长。
     *
     * @param ref 参考坐标下的矩形
     * @return 屏幕坐标下的矩形；缩放极小时宽高可能为 0，由调用方的裁剪校验拦下
     */
    fun toScreenRect(ref: PixelRect): PixelRect {
        val left = toScreenX(ref.x.toDouble()).roundToInt()
        val top = toScreenY(ref.y.toDouble()).roundToInt()
        val right = toScreenX((ref.x + ref.width).toDouble()).roundToInt()
        val bottom = toScreenY((ref.y + ref.height).toDouble()).roundToInt()
        return PixelRect(left, top, right - left, bottom - top)
    }

    companion object {
        /** 参考截图本身（2388×1080 测试机）：缩放 1，无偏移。 */
        val IDENTITY = Calibration(1.0, 1.0, 0.0, 0.0)

        /** 横纵缩放相同的换算，自动校准的结果用它创建。 */
        fun uniform(scale: Double, originX: Double, originY: Double): Calibration =
            Calibration(scale, scale, originX, originY)
    }
}
