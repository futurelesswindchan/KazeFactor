package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps

/**
 * 把一帧画面识别成 4 个槽位的结果。
 *
 * 当前按参考截图的固定坐标裁取槽位（scale = 1，不做校准），只适用于 2388×1080 的画面。
 * TODO(第 9 节第 3 步): 接入 `Calibration`，按校准结果换算槽位坐标。
 *
 * ```
 * val recognizer = FactorRecognizer(SlotMatcher(bank, questionTemplates))
 * val result = recognizer.recognize(screenshot)
 * result.factorIdsOrNull() // 例如 [3, 12, 40, 51]
 * ```
 *
 * @param matcher 单槽匹配器
 */
class FactorRecognizer(private val matcher: SlotMatcher) {

    /**
     * 识别整帧图像。
     *
     * @param frame 完整截图，尺寸须为 [ReferenceLayout.WIDTH] × [ReferenceLayout.HEIGHT]
     * @throws IllegalArgumentException 截图尺寸不符时
     */
    fun recognize(frame: ArgbImage): FrameResult = FrameResult(analyze(frame).map { it.result })

    /**
     * 同 [recognize]，但返回每个槽位的完整匹配过程（候选排名、距离），供阈值报告和调试使用。
     *
     * @param fullAnalysis 为 true 时问号槽位也计算全部候选距离
     */
    fun analyze(frame: ArgbImage, fullAnalysis: Boolean = false): List<SlotAnalysis> {
        require(frame.width == ReferenceLayout.WIDTH && frame.height == ReferenceLayout.HEIGHT) {
            "未校准时只支持 ${ReferenceLayout.WIDTH}x${ReferenceLayout.HEIGHT}，实际 ${frame.width}x${frame.height}"
        }
        return analyzeRegions(fullAnalysis) { rect -> ImageOps.crop(frame, rect.x, rect.y, rect.width, rect.height) }
    }

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

    companion object {
        /**
         * 第 [slotIndex] 槽的搜索区域：槽位矩形四边各外扩 [ReferenceLayout.SEARCH_MARGIN]。
         *
         * 区域边长等于 [SlotMatcher.regionSize]（scale = 1 时为 98）。
         */
        fun searchRect(slotIndex: Int): PixelRect =
            ReferenceLayout.slotRect(slotIndex).expand(ReferenceLayout.SEARCH_MARGIN)
    }
}
