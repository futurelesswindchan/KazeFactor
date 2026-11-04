package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.testutil.SyntheticFrames
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * 开关判断与画面状态（架构方案第 9 节第 3 步验收 ⑤，第 3b 步）。
 *
 * - 47 张实机截图：问号截图（`disable*`）开关必须判为关、画面状态为 [ScreenState.FACTOR_SCREEN_OFF]；
 *   已刷新截图开关必须判为开、画面状态为 [ScreenState.FACTOR_SCREEN_ON]。
 * - 合成分辨率抽查：缩放、黑边、JPEG 重压缩后用真实换算检测，结论不变。
 * - 绿色像素占比写入 `core-engine/build/reports/recognition/toggle-report.txt`，用来确认阈值余量。
 *
 * NOTE: 实机截图用 [Calibration.IDENTITY]，不跑完整校准。校准本身由 `CalibrationSyntheticTest` 验收，
 * 这里只验证「校准正确时开关判断是否正确」，避免每张图多花 0.3 秒做整屏粗定位。
 */
class ToggleDetectorTest {

    private val detector = ToggleDetector()
    private val recognizer = FactorRecognizer(TestFixtures.newMatcher())

    @Test
    fun `47张截图开关判断与画面状态全部正确`() {
        val failures = mutableListOf<String>()
        val ratiosOff = mutableListOf<Pair<String, Double>>()
        val ratiosOn = mutableListOf<Pair<String, Double>>()

        for (label in TestFixtures.labels) {
            val frame = TestFixtures.loadScreenshot(label.file)
            val expectOff = label.expected.all { it == SlotResult.Question }
            val ratio = detector.greenRatio(frame, Calibration.IDENTITY)
                ?: error("${label.file}：采样区超出画面")
            (if (expectOff) ratiosOff else ratiosOn) += label.file to ratio

            val toggle = detector.classify(ratio)
            val expectedToggle = if (expectOff) ToggleState.OFF else ToggleState.ON
            if (toggle != expectedToggle) {
                failures += "${label.file}：开关期望 $expectedToggle，实际 $toggle（绿色占比 ${pct(ratio)}）"
            }

            val state = ScreenState.classify(toggle, recognizer.recognize(frame))
            val expectedState = if (expectOff) ScreenState.FACTOR_SCREEN_OFF else ScreenState.FACTOR_SCREEN_ON
            if (state != expectedState) {
                failures += "${label.file}：画面状态期望 $expectedState，实际 $state"
            }
        }

        writeReport(ratiosOff, ratiosOn)
        assertEquals(7, ratiosOff.size, "问号截图数量")
        assertEquals(40, ratiosOn.size, "已刷新截图数量")
        if (failures.isNotEmpty()) fail("共 ${failures.size} 项不对：\n" + failures.joinToString("\n"))
    }

    @Test
    fun `合成分辨率下开关判断不变`() {
        val failures = mutableListOf<String>()
        for (file in SYNTHETIC_SOURCES) {
            val source = TestFixtures.loadScreenshot(file)
            val expected = if (file.startsWith("disable")) ToggleState.OFF else ToggleState.ON
            for ((scale, quality) in SYNTHETIC_CASES) {
                val synthetic = SyntheticFrames.scaleWithBorder(source, scale, padX = 30, padY = 20)
                val image = quality?.let { SyntheticFrames.jpegRecompress(synthetic.image, it) } ?: synthetic.image
                val ratio = detector.greenRatio(image, synthetic.truth)
                val actual = ratio?.let { detector.classify(it) } ?: ToggleState.UNCERTAIN
                if (actual != expected) {
                    failures += "$file 缩放 $scale JPEG ${quality ?: "无"}：期望 $expected，实际 $actual" +
                        "（绿色占比 ${ratio?.let { pct(it) } ?: "采样区超出画面"}）"
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `采样区超出画面时为不确定`() {
        val tiny = ArgbImage.filled(100, 100, GREEN)
        assertEquals(ToggleState.UNCERTAIN, detector.detect(tiny, Calibration.IDENTITY))
    }

    @Test
    fun `纯色画面按占比判定`() {
        val w = ReferenceLayout.WIDTH
        val h = ReferenceLayout.HEIGHT
        assertEquals(ToggleState.ON, detector.detect(ArgbImage.filled(w, h, GREEN), Calibration.IDENTITY))
        assertEquals(ToggleState.OFF, detector.detect(ArgbImage.filled(w, h, GRAY_BLUE), Calibration.IDENTITY))
    }

    @Test
    fun `占比正好等于阈值时为不确定`() {
        assertEquals(ToggleState.UNCERTAIN, detector.classify(detector.onThreshold))
        assertEquals(ToggleState.UNCERTAIN, detector.classify(detector.offThreshold))
        assertEquals(ToggleState.UNCERTAIN, detector.classify(0.3))
        assertEquals(ToggleState.ON, detector.classify(1.0))
        assertEquals(ToggleState.OFF, detector.classify(0.0))
    }

    @Test
    fun `绿色像素按G比RB都大40判断`() {
        assertEquals(true, ToggleDetector.isGreen(GREEN))
        assertEquals(false, ToggleDetector.isGreen(GRAY_BLUE))
        // 必须严格大于 40：差正好 40 的一侧不算
        assertEquals(false, ToggleDetector.isGreen(argb(60, 100, 59)), "G − R = 40")
        assertEquals(false, ToggleDetector.isGreen(argb(59, 100, 60)), "G − B = 40")
        assertEquals(true, ToggleDetector.isGreen(argb(59, 100, 59)), "两侧都是 41")
    }

    @Test
    fun `阈值顺序不对时拒绝构造`() {
        assertFailsWith<IllegalArgumentException> { ToggleDetector(onThreshold = 0.1, offThreshold = 0.5) }
        assertFailsWith<IllegalArgumentException> { ToggleDetector(onThreshold = 1.5) }
        assertFailsWith<IllegalArgumentException> { ToggleDetector(offThreshold = -0.1) }
    }

    @Test
    fun `画面状态判定表`() {
        val q = SlotResult.Question
        val f = SlotResult.Factor(1)
        val allQuestion = FrameResult(listOf(q, q, q, q))
        val allFactor = FrameResult(listOf(f, f, f, f))
        val mixed = FrameResult(listOf(q, f, q, q))

        assertEquals(ScreenState.FACTOR_SCREEN_ON, ScreenState.classify(ToggleState.ON, allFactor))
        // 开关开 + 问号：仍为 ON，由 Decision 按 UNCERTAIN 处理（架构方案第 3.3 节）
        assertEquals(ScreenState.FACTOR_SCREEN_ON, ScreenState.classify(ToggleState.ON, mixed))
        assertEquals(ScreenState.FACTOR_SCREEN_OFF, ScreenState.classify(ToggleState.OFF, allQuestion))
        // 开关关但槽位不全是问号：两种证据不一致
        assertEquals(ScreenState.NOT_FACTOR_SCREEN, ScreenState.classify(ToggleState.OFF, mixed))
        assertEquals(ScreenState.NOT_FACTOR_SCREEN, ScreenState.classify(ToggleState.OFF, allFactor))
        assertEquals(ScreenState.NOT_FACTOR_SCREEN, ScreenState.classify(ToggleState.UNCERTAIN, allQuestion))
        assertEquals(ScreenState.NOT_FACTOR_SCREEN, ScreenState.classify(ToggleState.UNCERTAIN, allFactor))
    }

    /** 两组的占比范围与阈值余量，供回写架构方案第 3.3 节。 */
    private fun writeReport(off: List<Pair<String, Double>>, on: List<Pair<String, Double>>) {
        val report = StringBuilder("# 开关判断报告\n\n")
        report.appendLine("采样区（参考坐标）：${ReferenceLayout.TOGGLE_SAMPLE_RECT}")
        report.appendLine("绿色像素：G − R > ${ToggleDetector.GREEN_MARGIN} 且 G − B > ${ToggleDetector.GREEN_MARGIN}")
        report.appendLine("阈值：> ${pct(detector.onThreshold)} 为开，< ${pct(detector.offThreshold)} 为关\n")

        val offMax = off.maxByOrNull { it.second }
        val onMin = on.minByOrNull { it.second }
        report.appendLine("开关关（${off.size} 张）：最大占比 ${offMax?.let { "${pct(it.second)}（${it.first}）" } ?: "-"}，" +
            "距关阈值余量 ${offMax?.let { pct(detector.offThreshold - it.second) } ?: "-"}")
        report.appendLine("开关开（${on.size} 张）：最小占比 ${onMin?.let { "${pct(it.second)}（${it.first}）" } ?: "-"}，" +
            "距开阈值余量 ${onMin?.let { pct(it.second - detector.onThreshold) } ?: "-"}")
        report.appendLine("\n## 逐张")
        (off + on).forEach { (file, ratio) -> report.appendLine("  $file  ${pct(ratio)}") }

        val out = File("build/reports/recognition/toggle-report.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString(), Charsets.UTF_8)
        println(report)
    }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)

    private companion object {
        /** 开关打开时的典型颜色 #4DB022。 */
        val GREEN = argb(0x4D, 0xB0, 0x22)

        /** 开关关闭时的典型颜色 #424A5D。 */
        val GRAY_BLUE = argb(0x42, 0x4A, 0x5D)

        val SYNTHETIC_SOURCES = listOf("disable01.jpg", "enable01.jpg")

        /** 缩放与 JPEG 质量，与 `CalibrationSyntheticTest` 的 4 种缩放一致。 */
        val SYNTHETIC_CASES = listOf(0.5 to 0.85f, 0.75 to 0.75f, 1.25 to null, 1.33 to 0.80f)

        fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
