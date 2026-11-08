package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.image.rgbAbsDiffSum

/**
 * 有位置档案时的快速核验（架构方案第 3.4.5 节）。不做任何搜索，只在档案给出的固定位置比较像素。
 *
 * 1. 用档案换算出开关区域（参考 x 1316..1439、y 592..627），与 [CalibrationProfile.toggleOnPatch] 比较平均像素差。
 * 2. 距离 ≤ [toggleThreshold]：因子界面且开关开，返回 [Result.Pass]。
 * 3. 距离在 [toggleThreshold] 与 [TOGGLE_FUZZY_MAX_DISTANCE] 之间：启动双保险，使用 [ToggleDetector] 检查
 *    采样区绿色像素占比。若为 [ToggleState.ON]，同样判定为通过（军师架构决策 2026-11-08）。
 * 4. 距离超过上限且不符合绿色兜底：再按档案位置识别 4 个槽位。都是问号返回 [Result.ToggleOff]（提示打开开关），
 *    否则返回 [Result.Fail]，调用方走第 3.4.2 节的流程（先自动校准，再手动引导）。
 *
 * 第 3.4.5 节第 2 条「通过后第一帧 4 个槽位都不能是 `Unknown`」需要正式识别的结果，
 * 由第 4 步的状态机在拿到第一帧时检查，不在这里做，避免同一帧识别两次。
 *
 * @param recognizerFactory 距离超过阈值时按档案位置识别槽位用
 * @param toggleThreshold 开关区域与参照块平均像素差（0~255）的首选上限
 * @param toggleDetector 用于双重保险的开关检测器
 */
open class ProfileVerifier(
    private val recognizerFactory: RecognizerFactory,
    val toggleThreshold: Double = DEFAULT_TOGGLE_THRESHOLD,
    private val toggleDetector: ToggleDetector = ToggleDetector()
) {

    /** 快速核验结果，都带上开关区域与参照块的距离，便于日志和测试报告。 */
    sealed class Result {
        /** 开关区域与参照块的平均像素差；尺寸不符或区域出画面时为 [Double.POSITIVE_INFINITY]。 */
        abstract val toggleDistance: Double

        /** 因子界面且开关开。 */
        data class Pass(override val toggleDistance: Double) : Result()

        /** 开关关闭：开关不像参照块，4 个槽位都是问号。 */
        data class ToggleOff(override val toggleDistance: Double) : Result()

        /** 核验失败：既不像开关开，也不是 4 个问号（多半不在因子界面，或位置已经变了）。 */
        data class Fail(override val toggleDistance: Double) : Result()
    }

    /**
     * 用 [profile] 核验 [frame]。
     *
     * @param frame 完整截图
     * @param profile 位置档案；截图尺寸与档案不符时直接 [Result.Fail]（尺寸变了就当作没有档案）
     */
    open fun verify(frame: ArgbImage, profile: CalibrationProfile): Result {
        if (!profile.matchesScreen(frame.width, frame.height)) return Result.Fail(Double.POSITIVE_INFINITY)
        val distance = toggleDistance(frame, profile)
        if (distance <= toggleThreshold) return Result.Pass(distance)

        // 双重保险：距离处于模糊区间（例如 15.0 ~ 25.0），但 ToggleDetector 明确检测到开关为 ON 时放行
        if (distance <= TOGGLE_FUZZY_MAX_DISTANCE) {
            val toggleState = toggleDetector.detect(frame, profile.toCalibration())
            if (toggleState == ToggleState.ON) {
                return Result.Pass(distance)
            }
        }

        return if (slotsAllQuestion(frame, profile.toCalibration())) {
            Result.ToggleOff(distance)
        } else {
            Result.Fail(distance)
        }
    }

    /**
     * 开关区域与参照块的平均像素差（RGB 三通道平均绝对差，0~255）。
     *
     * 区域按档案的换算求出，尺寸与 [captureTogglePatch] 截下参照块时相同（同一个换算、同一个参考矩形）。
     * 在 ±2 px 搜索微调以抵抗机型亚像素采样微差。
     *
     * @return 尺寸不符或区域超出画面时为 [Double.POSITIVE_INFINITY]
     */
    fun toggleDistance(frame: ArgbImage, profile: CalibrationProfile): Double {
        val rect = profile.toCalibration().toScreenRect(TOGGLE_PATCH_REF_RECT)
        val patch = profile.toggleOnPatch
        if (rect.width != patch.width || rect.height != patch.height) return Double.POSITIVE_INFINITY
        if (!rect.isInside(frame)) return Double.POSITIVE_INFINITY

        var minDiff = averagePixelDiff(ImageOps.crop(frame, rect.x, rect.y, rect.width, rect.height), patch)
        if (minDiff <= toggleThreshold) return minDiff

        for (dy in -2..2) {
            for (dx in -2..2) {
                if (dx == 0 && dy == 0) continue
                val curX = rect.x + dx
                val curY = rect.y + dy
                val inside = curX >= 0 && curY >= 0 && curX + rect.width <= frame.width && curY + rect.height <= frame.height
                if (inside) {
                    val diff = averagePixelDiff(ImageOps.crop(frame, curX, curY, rect.width, rect.height), patch)
                    if (diff < minDiff) {
                        minDiff = diff
                        if (minDiff <= toggleThreshold) return minDiff
                    }
                }
            }
        }
        return minDiff
    }

    /** 按 [calibration] 识别 4 个槽位，都是问号时为 true；搜索区域出画面时为 false。 */
    private fun slotsAllQuestion(frame: ArgbImage, calibration: Calibration): Boolean {
        val recognizer = recognizerFactory.create(calibration)
        val inside = (1..ReferenceLayout.SLOT_COUNT).all { recognizer.searchRect(it).isInside(frame) }
        return inside && recognizer.recognize(frame).isAllQuestion
    }

    companion object {
        /**
         * 开关参照块的参考矩形：x 1316..1439、y 592..627（架构方案第 3.4.5 节、调研报告实验 11）。
         */
        val TOGGLE_PATCH_REF_RECT = PixelRect(1316, 592, 124, 36)

        /** 阈值初值。实验 11：「开」与「开」距离 0.00，「开」与「关」距离 40.89。 */
        const val DEFAULT_TOGGLE_THRESHOLD = 15.0

        /** 模糊双保险判定上限。若在此范围内且 ToggleDetector 识别为绿色 ON，同样放行。 */
        const val TOGGLE_FUZZY_MAX_DISTANCE = 25.0

        /**
         * 设置位置时从用户屏幕截下开关参照块，存进 [CalibrationProfile.toggleOnPatch]。
         */
        fun captureTogglePatch(frame: ArgbImage, calibration: Calibration): ArgbImage? {
            val rect = calibration.toScreenRect(TOGGLE_PATCH_REF_RECT)
            if (rect.width <= 0 || rect.height <= 0 || !rect.isInside(frame)) return null
            return ImageOps.crop(frame, rect.x, rect.y, rect.width, rect.height)
        }

        /** 两张同尺寸图像的 RGB 三通道平均绝对差（0~255）。 */
        fun averagePixelDiff(a: ArgbImage, b: ArgbImage): Double {
            require(a.width == b.width && a.height == b.height) {
                "图像尺寸不一致：${a.width}x${a.height} 与 ${b.width}x${b.height}"
            }
            var sum = 0L
            for (i in a.pixels.indices) sum += rgbAbsDiffSum(a.pixels[i], b.pixels[i])
            return sum.toDouble() / (a.pixels.size * 3)
        }

        private fun PixelRect.isInside(frame: ArgbImage): Boolean =
            x >= 0 && y >= 0 && x + width <= frame.width && y + height <= frame.height
    }
}
