package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps

/**
 * 把一帧画面识别成 4 个槽位的结果。
 *
 * 通过 [calibration] 把参考坐标换算成屏幕坐标后裁取槽位，不再限制画面必须是 2388×1080。
 * 搜索区域以换算后的槽位左上角为准，裁固定的 [SlotMatcher.regionWidth] × [SlotMatcher.regionHeight]，
 * 避免因 [Calibration.toScreenRect] 的取整差（±1 px）导致区域尺寸不一致。
 *
 * ```
 * val recognizer = FactorRecognizer(matcher, calibration)
 * val result = recognizer.recognize(screenshot)
 * result.factorIdsOrNull() // 例如 [3, 12, 40, 51]
 * ```
 *
 * @param matcher 单槽匹配器，其 [SlotMatcher.regionWidth]、[SlotMatcher.regionHeight] 决定搜索区域的尺寸
 * @param calibration 参考坐标到当前屏幕的换算；不校准时传 [Calibration.IDENTITY]
 */
class FactorRecognizer(
    private val matcher: SlotMatcher,
    private val calibration: Calibration = Calibration.IDENTITY
) {

    /**
     * 识别整帧图像。
     *
     * @param frame 完整截图
     * @throws IllegalArgumentException 搜索区域超出画面边界时
     */
    fun recognize(frame: ArgbImage): FrameResult = FrameResult(analyze(frame).map { it.result })

    /**
     * 同 [recognize]，但返回每个槽位的完整匹配过程（候选排名、距离），供阈值报告和调试使用。
     *
     * @param fullAnalysis 为 true 时问号槽位也计算全部候选距离
     */
    fun analyze(frame: ArgbImage, fullAnalysis: Boolean = false): List<SlotAnalysis> =
        analyzeRegions(fullAnalysis) { rect -> ImageOps.crop(frame, rect.x, rect.y, rect.width, rect.height) }

    /**
     * 只按需读取槽位区域的版本，供以后的 `Frame.crop()` 使用，避免整屏转成像素数组。
     *
     * @param cropper 按屏幕矩形返回对应区域的图像
     * @param fullAnalysis 见 [analyze]
     */
    fun analyzeRegions(fullAnalysis: Boolean = false, cropper: (PixelRect) -> ArgbImage): List<SlotAnalysis> =
        (1..ReferenceLayout.SLOT_COUNT).map { slot ->
            matcher.match(cropper(searchRect(slot)), slot, fullAnalysis)
        }

    /**
     * 第 [slotIndex] 槽的搜索区域：以校准后的槽位左上角为准，裁固定尺寸。
     *
     * 尺寸等于 [SlotMatcher.regionWidth] × [SlotMatcher.regionHeight]
     * （= 槽位宽高 + 2 × [ReferenceLayout.SEARCH_MARGIN]），
     * 左上角从换算后的槽位左上角各减 [ReferenceLayout.SEARCH_MARGIN]。
     */
    fun searchRect(slotIndex: Int): PixelRect {
        val refSlot = ReferenceLayout.slotRect(slotIndex)
        val screenSlot = calibration.toScreenRect(refSlot)
        val margin = ReferenceLayout.SEARCH_MARGIN
        return PixelRect(screenSlot.x - margin, screenSlot.y - margin, matcher.regionWidth, matcher.regionHeight)
    }
}
