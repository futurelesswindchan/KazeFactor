package io.github.futurelesswindchan.kazefactor.engine.vision

/**
 * 参考截图（2388×1080，测试机）上各界面元素的坐标常量。
 *
 * 数值来自架构方案第 0 节对 47 张实机截图的逐像素分析。其他分辨率要先经校准
 * （第 9 节第 3 步的 `Calibration`）换算成屏幕坐标，不能直接使用。
 */
object ReferenceLayout {

    /** 参考截图宽度。 */
    const val WIDTH = 2388

    /** 参考截图高度。 */
    const val HEIGHT = 1080

    /** 槽位数量。 */
    const val SLOT_COUNT = 4

    /** 槽位边长，4 个槽位都是正方形。 */
    const val SLOT_SIZE = 90

    /** 4 个槽位左上角的纵坐标（所有截图一致）。 */
    const val SLOT_TOP = 248

    /** 第 1~4 槽左上角的横坐标，间距 163。 */
    private val SLOT_LEFTS = intArrayOf(1088, 1251, 1414, 1577)

    /** 多数因子图标在游戏中显示的长边长度。个别图标更小，见 [TemplateBank.DEFAULT_SIZE_FACTORS]。 */
    const val ICON_LONG_EDGE = 90

    /**
     * 裁取槽位时四边额外多取的像素数。
     *
     * 偏移精修最多 ±3 px，留 4 px 保证模板移动后仍完全落在裁剪区域内。
     */
    const val SEARCH_MARGIN = 4

    /** 刷新按钮循环箭头中心，即点击刷新的位置（10 px 粗定位）。 */
    const val REFRESH_TAP_X = 1380

    /** 见 [REFRESH_TAP_X]。 */
    const val REFRESH_TAP_Y = 432

    /**
     * 返回第 [slotIndex] 槽在参考截图上的矩形。
     *
     * @param slotIndex 槽位序号，1~4，与 `Factor.canAppearInSlot()` 一致
     * @throws IllegalArgumentException 序号不在 1~4 时
     */
    fun slotRect(slotIndex: Int): PixelRect {
        require(slotIndex in 1..SLOT_COUNT) { "槽位序号必须在 1~4 之间：$slotIndex" }
        return PixelRect(SLOT_LEFTS[slotIndex - 1], SLOT_TOP, SLOT_SIZE, SLOT_SIZE)
    }
}
