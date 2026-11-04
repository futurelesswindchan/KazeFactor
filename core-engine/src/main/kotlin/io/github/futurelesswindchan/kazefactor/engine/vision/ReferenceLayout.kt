package io.github.futurelesswindchan.kazefactor.engine.vision

/** 亮度跳变的方向，沿坐标增大方向看。 */
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
 * 参考截图（2388×1080，测试机）上各界面元素的坐标常量。
 *
 * 数值来自架构方案第 0 节对 47 张实机截图的逐像素分析，以及第 3.1 节的面板边框测量。
 * 其他分辨率要先经 [Calibration] 换算成屏幕坐标，不能直接使用。
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

    /**
     * 刷新按钮的点击位置：循环箭头中心。
     *
     * 由 `AnchorSurveyTest` 测得的箭头外框 x 1343..1411、y 399..467 算出（架构方案第 5 节）。
     * 箭头在开关关闭时几乎看不见，不能当锚点，但点击位置不受影响。
     */
    const val REFRESH_TAP_X = 1377

    /** 见 [REFRESH_TAP_X]。 */
    const val REFRESH_TAP_Y = 433

    /**
     * 帮助图标（「挑战因子」标题右侧的白色「?」，原称骰子）的矩形。
     * 开关开、关两种状态下像素完全相同（`AnchorSurveyTest`，47 张截图 std 为 0）。
     *
     * 模板文件为 `assets/recognition/help.png`，由 `RecognitionAssetGenerator` 从这个矩形裁出。
     *
     * NOTE: 同一屏上还有两个外观相同的图标，在 (2127, 125) 和约 (1952, 740)，
     * 粗定位必须保留多个候选，由面板边框精修筛掉假位置（架构方案第 3.1 节第 1 段）。
     */
    val HELP_ICON_RECT = PixelRect(1475, 125, 33, 38)

    // ---- 面板边框精修（架构方案第 3.1 节第 2 段）----
    // 位置都是「跳变后第一个像素」的下标，等于跳变处的像素边界，可直接交给 Calibration 换算。

    /**
     * 面板左右两侧竖条的 4 条竖边，用于求横向缩放和 X 偏移。
     *
     * 竖条比面板底色亮约 11 个亮度级（34 → 45），宽约 40 px；最外两条边相距 823 px。
     */
    val PANEL_VERTICAL_EDGES: List<ReferenceEdge> = listOf(
        ReferenceEdge(966, EdgePolarity.RISING),
        ReferenceEdge(1006, EdgePolarity.FALLING),
        ReferenceEdge(1747, EdgePolarity.RISING),
        ReferenceEdge(1789, EdgePolarity.FALLING)
    )

    /**
     * 用于求 Y 偏移的 3 条横边：槽位外框上沿、下沿，以及面板下边框。
     *
     * NOTE: 不用面板上边框（y 84）。enable33、39、40 中面板上方 y 60..90 一带有内容变化，
     * 用了会把槽位位置拉偏 7 px。
     */
    val PANEL_HORIZONTAL_EDGES: List<ReferenceEdge> = listOf(
        ReferenceEdge(236, EdgePolarity.RISING),
        ReferenceEdge(350, EdgePolarity.FALLING),
        ReferenceEdge(690, EdgePolarity.RISING)
    )

    /**
     * 求横向剖面时参与平均的行：y 200..660 每 4 行取 1 行，跳过 [HORIZONTAL_PROFILE_EXCLUDED_ROWS]。
     */
    val HORIZONTAL_PROFILE_ROWS: IntProgression = 200..660 step 4

    /** 横向剖面跳过的行：刷新按钮所在区域，按钮的明暗会干扰竖边。 */
    val HORIZONTAL_PROFILE_EXCLUDED_ROWS: IntRange = 370..505

    /**
     * 求纵向剖面时参与平均的列：槽位外框内侧的 4 组列，加面板两侧空白列。
     *
     * XXX: 这组列是按实验 8 当时的方框坐标定的，实际落在方框内侧 7~10 px，
     * 在上沿、下沿处照样能测到跳变。合成测试就是用这组列跑出来的，架构方案要求**按原样实现，不要改**；
     * 改动后需要重跑合成测试。
     */
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
     * @param slotIndex 槽位序号，1~4，与 `Factor.canAppearInSlot()` 一致
     * @throws IllegalArgumentException 序号不在 1~4 时
     */
    fun slotRect(slotIndex: Int): PixelRect {
        require(slotIndex in 1..SLOT_COUNT) { "槽位序号必须在 1~4 之间：$slotIndex" }
        return PixelRect(SLOT_LEFTS[slotIndex - 1], SLOT_TOP, SLOT_SIZE, SLOT_SIZE)
    }

    // ---- 开关检测（架构方案第 3.3 节）----

    /**
     * 开关采样区域在参考截图上的矩形：x 1320..1355, y 600..620。
     *
     * 开关打开时，滑轨左半段变成绿色。47 张截图中：
     * - 开关关（7 张）：组平均色约 #424A5D
     * - 开关开（40 张）：组平均色约 #4DB022
     *
     * 判断方法是统计该区域内「G 比 R、B 都大 40 以上」的绿色像素占比。
     */
    val TOGGLE_SAMPLE_RECT = PixelRect(1320, 600, 36, 21)

    // ---- 吸附算法（架构方案第 3.4.4 节）----
    // 槽位外框的精确坐标（像素边界），实验 11 第 1 轮修正后的值。
    // 方框四角有约 5 px 的倒角，竖边有效范围 y 244..344，横边在 (左沿+9)..(右沿−9)。

    /**
     * 4 个槽位外框的 8 条竖边（左沿升、右沿降），吸附时搜索 scaleX 和 originX。
     *
     * 第 0 节和实验 8 里的 1081/1185 等是倒角处（y 236..243）的位置，不是竖边本身。
     */
    val SLOT_FRAME_VERTICAL_EDGES: List<ReferenceEdge> = listOf(
        ReferenceEdge(1076, EdgePolarity.RISING),  ReferenceEdge(1190, EdgePolarity.FALLING),
        ReferenceEdge(1239, EdgePolarity.RISING),  ReferenceEdge(1353, EdgePolarity.FALLING),
        ReferenceEdge(1402, EdgePolarity.RISING),  ReferenceEdge(1516, EdgePolarity.FALLING),
        ReferenceEdge(1565, EdgePolarity.RISING),  ReferenceEdge(1680, EdgePolarity.FALLING)
    )

    /** 竖边的有效纵向范围（避开四角倒角）。 */
    val SLOT_FRAME_VERTICAL_VALID_Y: IntRange = 244..344

    /**
     * 槽位外框上沿（由暗变亮），吸附时用于求 scaleY 和 originY。
     *
     * 4 个方框共用这一行，吸附时按每个方框单独采样，算作 4 条边。
     */
    const val SLOT_FRAME_TOP_Y = 236

    /** 槽位外框下沿（由亮变暗），用法同 [SLOT_FRAME_TOP_Y]。 */
    const val SLOT_FRAME_BOTTOM_Y = 350

    /** 横边避开倒角：每个方框内左右各缩进 9 px。 */
    const val SLOT_FRAME_HORIZONTAL_INSET = 9
}
