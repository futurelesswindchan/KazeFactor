package io.github.futurelesswindchan.kazefactor.engine.vision

/** 亮度跳变的方向，沿坐标增大方向判定。 */
enum class EdgePolarity {
    /** 由暗变亮。 */
    RISING,

    /** 由亮变暗。 */
    FALLING
}

/**
 * 参考截图上的一条直线边缘。
 *
 * @property position 边界坐标（跳变后第一个像素的下标）；竖边为 x，横边为 y
 * @property polarity 跳变方向
 */
data class ReferenceEdge(val position: Int, val polarity: EdgePolarity)

/**
 * 参考截图（2388×1080）上各界面元素的坐标常量。
 *
 * 其他分辨率的画面须先通过 [Calibration] 换算为屏幕坐标后再使用。
 */
object ReferenceLayout {

    /** 参考截图宽度。 */
    const val WIDTH = 2388

    /** 参考截图高度。 */
    const val HEIGHT = 1080

    /** 槽位数量。 */
    const val SLOT_COUNT = 4

    /** 槽位边长，4 个槽位均为正方形。 */
    const val SLOT_SIZE = 90

    /** 4 个槽位左上角的参考纵坐标。 */
    const val SLOT_TOP = 248

    /** 第 1~4 槽左上角的参考横坐标，相邻间距为 163。 */
    private val SLOT_LEFTS = intArrayOf(1088, 1251, 1414, 1577)

    /** 多数因子图标在游戏内显示的长边像素长度。個別图标尺寸见 [TemplateBank.DEFAULT_SIZE_FACTORS]。 */
    const val ICON_LONG_EDGE = 90

    /**
     * 裁取槽位搜索区域时四边额外扩展的像素边距。
     *
     * 偏移精修范围为 ±6 px，保留 6 px 边距以保证模板平移后仍完整处于裁剪区域内。
     */
    const val SEARCH_MARGIN = 6

    /** 刷新按钮循环箭头中心的参考横坐标。 */
    const val REFRESH_TAP_X = 1377

    /** 刷新按钮循环箭头中心的参考纵坐标。 */
    const val REFRESH_TAP_Y = 433

    /**
     * 挑战因子标题右侧白色问号帮助图标的参考矩形。
     *
     * 模板文件为 `assets/recognition/help.png`。
     * NOTE: 同一画面中可能存在其他外观相近的问号图标，粗定位需保留多个候选并交由面板边框精修筛选。
     */
    val HELP_ICON_RECT = PixelRect(1475, 125, 33, 38)

    // ---- 面板边框精修参数（坐标均为跳变处的像素边界） ----

    /** 面板左右两侧竖条的 4 条竖边，用于拟合横向缩放系数与 X 偏移。 */
    val PANEL_VERTICAL_EDGES: List<ReferenceEdge> = listOf(
        ReferenceEdge(966, EdgePolarity.RISING),
        ReferenceEdge(1006, EdgePolarity.FALLING),
        ReferenceEdge(1747, EdgePolarity.RISING),
        ReferenceEdge(1789, EdgePolarity.FALLING)
    )

    /**
     * 用于拟合 Y 偏移的 3 条横边：槽位外框上沿、槽位外框下沿与面板下边框。
     *
     * NOTE: 不使用面板上边框（y = 84），以规避顶部背景动态内容对边缘梯度的干扰。
     */
    val PANEL_HORIZONTAL_EDGES: List<ReferenceEdge> = listOf(
        ReferenceEdge(236, EdgePolarity.RISING),
        ReferenceEdge(350, EdgePolarity.FALLING),
        ReferenceEdge(690, EdgePolarity.RISING)
    )

    /** 计算横向亮度剖面时参与平均的行范围（跳过 [HORIZONTAL_PROFILE_EXCLUDED_ROWS]）。 */
    val HORIZONTAL_PROFILE_ROWS: IntProgression = 200..660 step 4

    /** 横向亮度剖面跳过的行范围（刷新按钮所在区域）。 */
    val HORIZONTAL_PROFILE_EXCLUDED_ROWS: IntRange = 370..505

    /** 计算纵向亮度剖面时参与平均的列范围。 */
    val VERTICAL_PROFILE_COLUMNS: List<IntRange> = listOf(
        1083..1086,
        1180..1183,
        1572..1575,
        1669..1672,
        1012..1050,
        1702..1740
    )

    /**
     * 返回第 [slotIndex] 槽在参考截图上的矩形。
     *
     * @param slotIndex 槽位序号（1~4）
     * @throws IllegalArgumentException 当序号不在 1~4 范围内时抛出
     */
    fun slotRect(slotIndex: Int): PixelRect {
        require(slotIndex in 1..SLOT_COUNT) { "槽位序号必须在 1~4 之间：$slotIndex" }
        return PixelRect(SLOT_LEFTS[slotIndex - 1], SLOT_TOP, SLOT_SIZE, SLOT_SIZE)
    }

    // ---- 开关状态检测参数 ----

    /**
     * 挑战因子开关采样区域的参考矩形。
     *
     * 开关开启时滑轨左半段呈绿色（约 #4DB022），关闭时呈暗灰蓝色（约 #424A5D）。
     */
    val TOGGLE_SAMPLE_RECT = PixelRect(1320, 600, 36, 21)

    // ---- 手动边缘吸附参数 ----

    /** 4 个槽位外框的 8 条竖边参考边界坐标（左沿由暗变亮，右沿由亮变暗）。 */
    val SLOT_FRAME_VERTICAL_EDGES: List<ReferenceEdge> = listOf(
        ReferenceEdge(1076, EdgePolarity.RISING),  ReferenceEdge(1190, EdgePolarity.FALLING),
        ReferenceEdge(1239, EdgePolarity.RISING),  ReferenceEdge(1353, EdgePolarity.FALLING),
        ReferenceEdge(1402, EdgePolarity.RISING),  ReferenceEdge(1516, EdgePolarity.FALLING),
        ReferenceEdge(1565, EdgePolarity.RISING),  ReferenceEdge(1680, EdgePolarity.FALLING)
    )

    /** 槽位外框竖边的有效纵向参考范围（避开四角倒角）。 */
    val SLOT_FRAME_VERTICAL_VALID_Y: IntRange = 244..344

    /** 槽位外框上沿参考纵坐标（由暗变亮）。 */
    const val SLOT_FRAME_TOP_Y = 236

    /** 槽位外框下沿参考纵坐标（由亮变暗）。 */
    const val SLOT_FRAME_BOTTOM_Y = 350

    /** 槽位外框横边采样时左右两端各自内缩的参考像素数（避开四角倒角）。 */
    const val SLOT_FRAME_HORIZONTAL_INSET = 9
}
