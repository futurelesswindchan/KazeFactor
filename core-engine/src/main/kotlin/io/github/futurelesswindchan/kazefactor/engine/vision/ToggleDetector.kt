package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.image.blueOf
import io.github.futurelesswindchan.kazefactor.engine.image.greenOf
import io.github.futurelesswindchan.kazefactor.engine.image.redOf

/**
 * 挑战因子开关检测（架构方案第 3.3 节「开关判断」）。
 *
 * 开关打开时滑轨左半段变成绿色。在 [ReferenceLayout.TOGGLE_SAMPLE_RECT] 内统计
 * 「G 比 R、B 都大 [GREEN_MARGIN] 以上」的像素占比：
 * 高于 [onThreshold] 为开，低于 [offThreshold] 为关，介于两者之间为 [ToggleState.UNCERTAIN]。
 *
 * 只看颜色的相对关系（G 明显高于 R、B），不看绝对亮度，对设备之间的亮度差异不敏感。
 * 这条规则用于**没有位置档案时**（自动校准阶段）；有档案时由第 3c 步的 `ProfileVerifier`
 * 改用用户屏幕上截下的开关参照块比较（第 3.4.5 节）。
 *
 * ```
 * val detector = ToggleDetector()
 * val toggle = detector.detect(screenshot, calibration)
 * val state = ScreenState.classify(toggle, recognizer.recognize(screenshot))
 * ```
 *
 * NOTE: 本类不可变，可在多个协程间共享。
 *
 * @param onThreshold 绿色像素占比高于此值判为开
 * @param offThreshold 绿色像素占比低于此值判为关
 * @throws IllegalArgumentException 阈值不满足 `0 ≤ offThreshold ≤ onThreshold ≤ 1` 时
 */
class ToggleDetector(
    val onThreshold: Double = DEFAULT_ON_THRESHOLD,
    val offThreshold: Double = DEFAULT_OFF_THRESHOLD
) {
    init {
        require(offThreshold in 0.0..onThreshold && onThreshold <= 1.0) {
            "阈值须满足 0 ≤ offThreshold ≤ onThreshold ≤ 1：off = $offThreshold，on = $onThreshold"
        }
    }

    /**
     * 检测整帧画面里开关的状态。
     *
     * @param frame 完整截图
     * @param calibration 参考坐标到当前屏幕的换算
     * @return 采样区超出画面或换算后面积为 0 时返回 [ToggleState.UNCERTAIN]
     */
    fun detect(frame: ArgbImage, calibration: Calibration): ToggleState =
        greenRatio(frame, calibration)?.let { classify(it) } ?: ToggleState.UNCERTAIN

    /**
     * 采样区内绿色像素的占比（0~1），供 [detect] 和阈值报告使用。
     *
     * @return 采样区超出画面或面积为 0 时返回 null
     */
    fun greenRatio(frame: ArgbImage, calibration: Calibration): Double? {
        val r = sampleRect(calibration)
        val inside = r.width > 0 && r.height > 0 &&
            r.x >= 0 && r.y >= 0 && r.x + r.width <= frame.width && r.y + r.height <= frame.height
        if (!inside) return null
        return greenRatio(ImageOps.crop(frame, r.x, r.y, r.width, r.height))
    }

    /**
     * 已裁好的采样区域内绿色像素的占比。
     *
     * 单独公开是为了以后接 `Frame.crop()` 时只读这一小块像素，不必把整屏转成数组
     * （与 [FactorRecognizer.analyzeRegions] 同样的考虑）。
     */
    fun greenRatio(region: ArgbImage): Double =
        region.pixels.count { isGreen(it) }.toDouble() / region.pixels.size

    /** 按占比判定开关状态。边界值（正好等于阈值）归入 [ToggleState.UNCERTAIN]，与架构方案的「> 50%」「< 10%」一致。 */
    fun classify(ratio: Double): ToggleState = when {
        ratio > onThreshold -> ToggleState.ON
        ratio < offThreshold -> ToggleState.OFF
        else -> ToggleState.UNCERTAIN
    }

    /** 采样区在当前屏幕上的矩形。 */
    fun sampleRect(calibration: Calibration): PixelRect =
        calibration.toScreenRect(ReferenceLayout.TOGGLE_SAMPLE_RECT)

    companion object {
        /** 开阈值初值（架构方案第 3.3 节），按 47 张截图实测确认。 */
        const val DEFAULT_ON_THRESHOLD = 0.5

        /** 关阈值初值，同上。 */
        const val DEFAULT_OFF_THRESHOLD = 0.1

        /** G 通道至少比 R、B 各大这么多，才算绿色像素。 */
        const val GREEN_MARGIN = 40

        /** 判断单个像素是否为开关打开时的绿色。 */
        fun isGreen(argb: Int): Boolean {
            val g = greenOf(argb)
            return g - redOf(argb) > GREEN_MARGIN && g - blueOf(argb) > GREEN_MARGIN
        }
    }
}
