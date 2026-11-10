package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [Calibration] 坐标换算、[PanelEdgeRefiner] 动态残差阈值、[Snapper] 搜索常量与 [Calibrator] 居中几何兜底单元测试。
 */
class CalibrationTest {

    @Test
    fun `IDENTITY 不改变参考坐标`() {
        for (slot in 1..ReferenceLayout.SLOT_COUNT) {
            val ref = ReferenceLayout.slotRect(slot)
            assertEquals(ref, Calibration.IDENTITY.toScreenRect(ref))
        }
        assertEquals(ReferenceLayout.HELP_ICON_RECT, Calibration.IDENTITY.toScreenRect(ReferenceLayout.HELP_ICON_RECT))
    }

    @Test
    fun 等比缩小一半并带偏移() {
        val cal = Calibration.uniform(scale = 0.5, originX = 10.0, originY = 20.0)
        // 左 10 + 0.5×1088 = 554，右 10 + 0.5×1178 = 599；上 20 + 0.5×248 = 144，下 20 + 0.5×338 = 189
        assertEquals(PixelRect(554, 144, 45, 45), cal.toScreenRect(ReferenceLayout.slotRect(1)))
        assertEquals(cal.scaleX, cal.scaleY)
    }

    @Test
    fun `放大 1_25 倍时边长随位置相差 1 px，但边界始终按公式取整`() {
        val cal = Calibration.uniform(scale = 1.25, originX = 0.0, originY = 0.0)
        // 纵向：上 1.25×248 = 310，下 1.25×338 = 422.5 → 423，高 113
        // 第 1 槽：左 1.25×1088 = 1360，右 1.25×1178 = 1472.5 → 1473，宽 113
        assertEquals(PixelRect(1360, 310, 113, 113), cal.toScreenRect(ReferenceLayout.slotRect(1)))
        // 第 2 槽：左 1.25×1251 = 1563.75 → 1564，右 1.25×1341 = 1676.25 → 1676，宽 112
        assertEquals(PixelRect(1564, 310, 112, 113), cal.toScreenRect(ReferenceLayout.slotRect(2)))
    }

    @Test
    fun 相邻矩形换算后首尾相接() {
        val cal = Calibration.uniform(scale = 1.33, originX = 0.3, originY = -7.7)
        val a = cal.toScreenRect(PixelRect(100, 50, 17, 23))
        val b = cal.toScreenRect(PixelRect(117, 73, 17, 23))
        assertEquals(a.x + a.width, b.x)
        assertEquals(a.y + a.height, b.y)
    }

    @Test
    fun 横纵缩放不等() {
        val cal = Calibration(scaleX = 1.1, scaleY = 0.9, originX = -5.5, originY = 12.25)
        // 左 -5.5 + 1.1×1475 = 1617，右 -5.5 + 1.1×1508 = 1653.3 → 1653
        // 上 12.25 + 0.9×125 = 124.75 → 125，下 12.25 + 0.9×163 = 158.95 → 159
        assertEquals(PixelRect(1617, 125, 36, 34), cal.toScreenRect(ReferenceLayout.HELP_ICON_RECT))
    }

    @Test
    fun 帮助图标矩形已跟着改名() {
        assertEquals(PixelRect(1475, 125, 33, 38), ReferenceLayout.HELP_ICON_RECT)
    }

    @Test
    fun `面板竖边按边界换算，最外两边间距随缩放变化`() {
        val cal = Calibration.uniform(scale = 0.75, originX = 40.0, originY = 0.0)
        val edges = ReferenceLayout.PANEL_VERTICAL_EDGES
        val first = cal.toScreenX(edges.first().position.toDouble())
        val last = cal.toScreenX(edges.last().position.toDouble())
        assertEquals(40.0 + 0.75 * 966, first, EPS)
        assertEquals(0.75 * 823, last - first, EPS)
    }

    @Test
    fun 屏幕坐标可以反算回参考坐标() {
        val cal = Calibration(scaleX = 0.8125, scaleY = 1.07, originX = 33.4, originY = -12.0)
        for (ref in doubleArrayOf(0.0, 1377.0, 2388.0)) {
            assertEquals(ref, cal.toReferenceX(cal.toScreenX(ref)), EPS)
            assertEquals(ref, cal.toReferenceY(cal.toScreenY(ref)), EPS)
        }
    }

    @Test
    fun 非法参数直接拒绝() {
        assertFailsWith<IllegalArgumentException> { Calibration(0.0, 1.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Calibration(1.0, -0.5, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Calibration(Double.NaN, 1.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Calibration(1.0, Double.POSITIVE_INFINITY, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Calibration(1.0, 1.0, Double.NaN, 0.0) }
    }

    @Test
    fun 面板剖面参数与参考布局一致() {
        assertEquals(listOf(966, 1006, 1747, 1789), ReferenceLayout.PANEL_VERTICAL_EDGES.map { it.position })
        assertEquals(
            listOf(EdgePolarity.RISING, EdgePolarity.FALLING, EdgePolarity.RISING, EdgePolarity.FALLING),
            ReferenceLayout.PANEL_VERTICAL_EDGES.map { it.polarity }
        )
        assertEquals(listOf(236, 350, 690), ReferenceLayout.PANEL_HORIZONTAL_EDGES.map { it.position })
        assertEquals(
            listOf(EdgePolarity.RISING, EdgePolarity.FALLING, EdgePolarity.RISING),
            ReferenceLayout.PANEL_HORIZONTAL_EDGES.map { it.polarity }
        )
        // y 200..660 每 4 行共 116 行，其中 372..504 的 34 行落在刷新按钮区域内被跳过
        val usedRows = ReferenceLayout.HORIZONTAL_PROFILE_ROWS
            .filterNot { it in ReferenceLayout.HORIZONTAL_PROFILE_EXCLUDED_ROWS }
        assertEquals(82, usedRows.size)
        assertEquals(6, ReferenceLayout.VERTICAL_PROFILE_COLUMNS.size)
    }

    @Test
    fun `RefineResult 动态残差容差随缩放系数放宽`() {
        val dummySource = HelpIconEstimate(1.0, 1475, 125, 2.0)
        // 1080p 及以下：下限为 2.5 px
        val r1080 = PanelEdgeRefiner.RefineResult(
            calibration = Calibration.uniform(0.8, 0.0, 0.0),
            horizontalResidual = 2.4,
            verticalResidual = 2.1,
            source = dummySource
        )
        assertEquals(2.5, r1080.maxAllowedResidual, EPS)
        assertTrue(r1080.passesResidualCheck)

        // 1260p（scale = 1260 / 1080 = 1.16667）：maxAllowedResidual = 2.5 * 1.16667 = 2.916675 px
        val scale1260 = 1260.0 / 1080.0
        val r1260Pass = PanelEdgeRefiner.RefineResult(
            calibration = Calibration.uniform(scale1260, -33.0, 0.0),
            horizontalResidual = 1.2,
            verticalResidual = 2.2,
            source = dummySource
        )
        assertEquals(2.5 * scale1260, r1260Pass.maxAllowedResidual, EPS)
        assertTrue(r1260Pass.passesResidualCheck)

        val r1260Fail = r1260Pass.copy(verticalResidual = 3.0)
        assertFalse(r1260Fail.passesResidualCheck)
    }

    @Test
    fun `Snapper 搜索范围收敛为微调区间`() {
        assertEquals(0.05, Snapper.SCALE_SEARCH_RANGE, EPS)
        assertEquals(24, Snapper.OFFSET_SEARCH_RANGE)
        assertEquals(0.85, Snapper.MIN_EDGE_COVERAGE, EPS)
    }

    @Test
    fun `当面板边框精修失败时 Calibrator 自动通过居中几何兜底完成校准`() {
        val source = TestFixtures.loadScreenshot("enable01.jpg")
        // 构造一份遮蔽左侧面板竖边（x = 950..1020）的图像，使 PanelEdgeRefiner 无法找到竖边跳变，迫使走居中几何兜底
        val pixels = source.pixels.copyOf()
        for (y in 180..700) {
            val rowBase = y * source.width
            for (x in 950..1020) {
                pixels[rowBase + x] = 0xFF202020.toInt()
            }
        }
        val occludedFrame = ArgbImage(source.width, source.height, pixels)
        val calibrator = Calibrator(TestFixtures.helpIconTemplate, TestFixtures.recognizerFactory)

        // 确认面板边框精修路径返回 null
        val candidates = calibrator.locateHelpIcon(occludedFrame)
        assertEquals(null, calibrator.refineCandidates(occludedFrame, candidates))

        // 确认完整校准通过居中几何兜底返回正确结果
        val fallbackCalib = assertNotNull(calibrator.calibrate(occludedFrame))
        assertEquals(1.0, fallbackCalib.scaleX, EPS)
        assertEquals(1.0, fallbackCalib.scaleY, EPS)
        assertEquals(0.0, fallbackCalib.originX, 4.0)
        assertEquals(0.0, fallbackCalib.originY, EPS)
    }

    private companion object {
        const val EPS = 1e-9
    }
}
