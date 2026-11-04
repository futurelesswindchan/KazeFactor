package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage

/**
 * 位置档案：换算 + 点击位置 + 开关参照块（架构方案第 3.4.1 节）。
 *
 * 设置成功（并经用户确认预览）后保存一份，之后每次点开始都先用 [ProfileVerifier] 快速核验，不再每次校准。
 * 以截图尺寸为键：尺寸变了（旋转、分屏、换设备）就当作没有档案，见 [matchesScreen]。
 *
 * 本类是纯数据，不含持久化。文本编解码见 [CalibrationProfileCodec]，
 * 存进 DataStore（`calibration_profile`）由 app 在第 6 步实现。
 *
 * ```
 * val patch = ProfileVerifier.captureTogglePatch(frame, calibration) ?: return
 * val profile = CalibrationProfile.create(frame.width, frame.height, calibration, patch, Source.AUTO, now)
 * ```
 *
 * NOTE: [toggleOnPatch] 是 [ArgbImage]，它没有重写 equals，所以两个档案的 `==` 比较的是同一个图像对象。
 * 比较内容请用 [contentEquals]。
 *
 * @property screenWidth 截图宽度，档案的键之一
 * @property screenHeight 截图高度，档案的键之一
 * @property scaleX 横向缩放，`屏幕 x = originX + scaleX × 参考 x`
 * @property scaleY 纵向缩放；自动校准与 [scaleX] 相等，手动吸附可以不等
 * @property originX 参考坐标 0 对应的屏幕横坐标（边界坐标）
 * @property originY 参考坐标 0 对应的屏幕纵坐标
 * @property tapX 刷新按钮点击位置的参考横坐标。自动校准为 [ReferenceLayout.REFRESH_TAP_X]；
 *   手动引导为用户点的屏幕位置经 [Calibration.toReferenceX] 反算的值
 * @property tapY 点击位置的参考纵坐标
 * @property toggleOnPatch 开关区域在「开」状态下的像素块，从用户屏幕截下（[ProfileVerifier.captureTogglePatch]）
 * @property source 来源，只用于日志和问题报告
 * @property createdAt 设置时间，Unix 毫秒
 * @throws IllegalArgumentException 截图尺寸非正，或换算参数非法（规则同 [Calibration]），或点击位置不是有限值时
 */
data class CalibrationProfile(
    val screenWidth: Int,
    val screenHeight: Int,
    val scaleX: Double,
    val scaleY: Double,
    val originX: Double,
    val originY: Double,
    val tapX: Double,
    val tapY: Double,
    val toggleOnPatch: ArgbImage,
    val source: Source,
    val createdAt: Long
) {
    init {
        require(screenWidth > 0 && screenHeight > 0) { "截图尺寸必须为正数：${screenWidth}x$screenHeight" }
        require(tapX.isFinite() && tapY.isFinite()) { "点击位置必须为有限值：($tapX, $tapY)" }
        toCalibration() // 借 Calibration 的校验拦下非法缩放和偏移
    }

    /** 还原换算，不需要重新搜索。 */
    fun toCalibration(): Calibration = Calibration(scaleX, scaleY, originX, originY)

    /** 刷新按钮在屏幕上的点击位置（边界坐标，不取整）。 */
    fun tapScreenX(): Double = toCalibration().toScreenX(tapX)

    /** 见 [tapScreenX]。 */
    fun tapScreenY(): Double = toCalibration().toScreenY(tapY)

    /** 这份档案是否适用于 [width] × [height] 的截图。 */
    fun matchesScreen(width: Int, height: Int): Boolean = width == screenWidth && height == screenHeight

    /** 逐字段比较，[toggleOnPatch] 按像素内容比较。 */
    fun contentEquals(other: CalibrationProfile): Boolean =
        copy(toggleOnPatch = other.toggleOnPatch) == other &&
            toggleOnPatch.width == other.toggleOnPatch.width &&
            toggleOnPatch.height == other.toggleOnPatch.height &&
            toggleOnPatch.pixels.contentEquals(other.toggleOnPatch.pixels)

    /** 档案来源。 */
    enum class Source {
        /** 自动校准（第 3.1 节）。 */
        AUTO,

        /** 手动引导 + 吸附（第 3.4.3、3.4.4 节）。 */
        MANUAL
    }

    companion object {
        /**
         * 由换算创建档案。
         *
         * @param tapX 点击位置的参考横坐标，默认为内置值（自动校准用）
         * @param tapY 点击位置的参考纵坐标
         */
        fun create(
            screenWidth: Int,
            screenHeight: Int,
            calibration: Calibration,
            toggleOnPatch: ArgbImage,
            source: Source,
            createdAt: Long,
            tapX: Double = ReferenceLayout.REFRESH_TAP_X.toDouble(),
            tapY: Double = ReferenceLayout.REFRESH_TAP_Y.toDouble()
        ): CalibrationProfile = CalibrationProfile(
            screenWidth, screenHeight,
            calibration.scaleX, calibration.scaleY, calibration.originX, calibration.originY,
            tapX, tapY, toggleOnPatch, source, createdAt
        )
    }
}
