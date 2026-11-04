package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.testutil.SyntheticFrames
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 第 3c 步：手动吸附、快速核验与位置档案（架构方案第 9 节第 3c 行）。
 *
 * - 验收 ①：用户框误差 ±30 px、横向拉伸 0.9 / 1.1 / 1.2 倍的合成图，吸附后槽位误差 ≤ 2 px。
 * - 验收 ②：初值故意偏一个槽位（163 px），吸附必须失败（逐边检查拦下）。
 * - 验收 ③：快速核验，47 张截图中开关开的全部通过，开关关的全部判为「开关关闭」。
 * - 验收 ④：档案序列化后再读回，换算结果不变。
 *
 * 吸附报告写入 `core-engine/build/reports/recognition/snap-report.txt`，快速核验报告写入 `verify-report.txt`。
 */
class SnapperProfileTest {

    private val snapper = Snapper()

    // ---- 验收 ① ----

    @Test
    fun `用户框有误差时吸附到正确位置`() {
        val report = StringBuilder("# 吸附报告\n\n")
        report.appendLine("用户框误差 e：左沿 +e、右沿 −e、上沿 +e（e > 0 时框偏小偏下，e < 0 时偏大偏上）。")
        report.appendLine("槽位误差：4 个槽位 16 条边界相对真值的最大偏差（px），上限 $MAX_SLOT_ERROR。")
        report.appendLine("最差边：16 条边覆盖率的最小值，通过要求 ≥ ${Snapper.MIN_EDGE_COVERAGE}。")
        report.appendLine("断言：通过检查的结果槽位误差必须 ≤ $MAX_SLOT_ERROR px；原始截图框完全准确时必须通过。拒收只记入汇总。\n")
        val failures = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var total = 0

        for (case in CASES) {
            report.appendLine("## ${case.name}")
            for (file in SOURCES) {
                val source = TestFixtures.loadScreenshot(file)
                val synthetic = SyntheticFrames.scaleWithBorderXY(source, case.scaleX, case.scaleY, case.padX, case.padY)
                val image = case.jpegQuality?.let { SyntheticFrames.jpegRecompress(synthetic.image, it) } ?: synthetic.image
                val truthInput = UserFrameInput.fromCalibration(synthetic.truth)
                for (e in USER_ERRORS) {
                    val input = UserFrameInput(truthInput.left + e, truthInput.right - e, truthInput.top + e)
                    val result = snapper.measure(image, input)
                    val slotError = maxSlotError(result.calibration, synthetic.truth)
                    val passed = result.passesEdgeCheck
                    val ok = passed && slotError <= MAX_SLOT_ERROR
                    report.appendLine(
                        "  ${if (ok) "  " else "✘ "}$file e = ${"%+d".format(e)}：${if (passed) "通过" else "拒收"}，" +
                            "槽位误差 ${"%.2f".format(slotError)} px，最差边 ${"%.3f".format(result.worstCoverage)}，" +
                            "scale (${"%.4f".format(result.calibration.scaleX)}, ${"%.4f".format(result.calibration.scaleY)})"
                    )
                    val tag = "[${case.name}] $file e = $e"
                    total++
                    if (!passed) rejected += tag
                    // 硬性要求：通过了检查就必须是对的。被拒收只是让用户重新对准，实验 11 里也有拒收
                    if (passed && slotError > MAX_SLOT_ERROR) {
                        failures += "$tag：通过逐边检查但槽位误差 ${"%.2f".format(slotError)} px（给出了错误坐标）"
                    }
                    // 原始截图、用户框完全准确时必须通过，否则说明坐标或梯度实现有问题
                    if (case.mustPassExact && e == 0 && !passed) {
                        failures += "$tag：原始截图、框完全准确时被拒收，最差边 ${"%.3f".format(result.worstCoverage)}"
                    }
                }
            }
            report.appendLine()
        }

        report.appendLine("## 汇总\n通过 ${total - rejected.size} / $total，拒收 ${rejected.size}：")
        rejected.forEach { report.appendLine("  $it") }
        writeReport("snap-report.txt", report)
        if (failures.isNotEmpty()) fail("共 ${failures.size} 项不达标：\n" + failures.joinToString("\n"))
    }

    // ---- 验收 ② ----

    @Test
    fun `初值偏一个槽位时吸附失败`() {
        val failures = mutableListOf<String>()
        for (file in SOURCES) {
            val frame = TestFixtures.loadScreenshot(file)
            val truth = UserFrameInput.fromCalibration(Calibration.IDENTITY)
            for (shift in doubleArrayOf(SLOT_PITCH, -SLOT_PITCH)) {
                val input = UserFrameInput(truth.left + shift, truth.right + shift, truth.top)
                val result = snapper.measure(frame, input)
                if (result.passesEdgeCheck) {
                    failures += "$file 偏 ${"%+.0f".format(shift)} px：通过了逐边检查，" +
                        "槽位误差 ${"%.2f".format(maxSlotError(result.calibration, Calibration.IDENTITY))} px"
                }
                assertNull(snapper.snap(frame, input), "$file 偏 $shift px 时 snap 应返回 null")
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `用户框左右颠倒时拒绝构造`() {
        assertFailsWith<IllegalArgumentException> { UserFrameInput(left = 500.0, right = 400.0, top = 100.0) }
    }

    // ---- 验收 ③ ----

    @Test
    fun `快速核验47张截图`() {
        val verifier = ProfileVerifier(TestFixtures.recognizerFactory)
        // 真实使用中参照块总是在开关开时截下的，这里统一取 enable01 的
        val reference = TestFixtures.loadScreenshot("enable01.jpg")
        val patch = assertNotNull(ProfileVerifier.captureTogglePatch(reference, Calibration.IDENTITY))
        val profile = CalibrationProfile.create(
            reference.width, reference.height, Calibration.IDENTITY, patch, CalibrationProfile.Source.AUTO, 0L
        )

        val report = StringBuilder("# 快速核验报告\n\n")
        report.appendLine("参照块：enable01.jpg 的开关区域 ${ProfileVerifier.TOGGLE_PATCH_REF_RECT}；阈值 ${verifier.toggleThreshold}\n")
        val failures = mutableListOf<String>()
        for (label in TestFixtures.labels) {
            val frame = TestFixtures.loadScreenshot(label.file)
            val result = verifier.verify(frame, profile)
            val expectOff = label.expected.all { it == SlotResult.Question }
            val ok = if (expectOff) result is ProfileVerifier.Result.ToggleOff else result is ProfileVerifier.Result.Pass
            report.appendLine(
                "  ${if (ok) "  " else "✘ "}${label.file}  距离 ${"%.2f".format(result.toggleDistance)}  → ${result::class.simpleName}"
            )
            if (!ok) {
                failures += "${label.file}：期望 ${if (expectOff) "ToggleOff" else "Pass"}，实际 $result"
            }
        }
        writeReport("verify-report.txt", report)
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `截图尺寸与档案不符时核验失败`() {
        val reference = TestFixtures.loadScreenshot("enable01.jpg")
        val patch = ProfileVerifier.captureTogglePatch(reference, Calibration.IDENTITY)!!
        val profile = CalibrationProfile.create(
            reference.width, reference.height, Calibration.IDENTITY, patch, CalibrationProfile.Source.AUTO, 0L
        )
        val other = ArgbImage.filled(1920, 1080, BLACK)
        val result = ProfileVerifier(TestFixtures.recognizerFactory).verify(other, profile)
        assertTrue(result is ProfileVerifier.Result.Fail, "实际 $result")
    }

    @Test
    fun `非因子画面核验失败`() {
        val reference = TestFixtures.loadScreenshot("enable01.jpg")
        val patch = ProfileVerifier.captureTogglePatch(reference, Calibration.IDENTITY)!!
        val profile = CalibrationProfile.create(
            reference.width, reference.height, Calibration.IDENTITY, patch, CalibrationProfile.Source.AUTO, 0L
        )
        // 同尺寸的纯黑画面：开关不像参照块，槽位也不是问号
        val black = ArgbImage.filled(reference.width, reference.height, BLACK)
        val result = ProfileVerifier(TestFixtures.recognizerFactory).verify(black, profile)
        assertTrue(result is ProfileVerifier.Result.Fail, "实际 $result")
    }

    // ---- 验收 ④ ----

    @Test
    fun `档案序列化后读回换算不变`() {
        val reference = TestFixtures.loadScreenshot("enable01.jpg")
        // 横纵不等、带小数的换算，覆盖手动吸附的情况
        val calibration = Calibration(1.1234567890123, 0.98765432109876, -12.3456789, 45.6789012345)
        val patch = ArgbImage(3, 2, intArrayOf(0xFF4DB022.toInt(), 0xFF424A5D.toInt(), 0x80123456.toInt(), 0, -1, 0xFF000000.toInt()))
        val profile = CalibrationProfile.create(
            1920, 1080, calibration, patch, CalibrationProfile.Source.MANUAL, 1_793_000_000_123L,
            tapX = 1380.25, tapY = 432.75
        )
        val codec = pngCodec()
        val text = codec.encode(profile)
        val restored = assertNotNull(codec.decode(text), "读回失败：\n$text")

        assertTrue(profile.contentEquals(restored), "读回的档案与原档案不同")
        assertEquals(calibration, restored.toCalibration())
        for (slot in 1..ReferenceLayout.SLOT_COUNT) {
            val r = ReferenceLayout.slotRect(slot)
            assertEquals(calibration.toScreenRect(r), restored.toCalibration().toScreenRect(r))
        }
        assertEquals(profile.tapScreenX(), restored.tapScreenX())
        assertEquals(profile.tapScreenY(), restored.tapScreenY())

        // 用真实截图截的参照块也能完整往返
        val realPatch = ProfileVerifier.captureTogglePatch(reference, Calibration.IDENTITY)!!
        val real = CalibrationProfile.create(
            reference.width, reference.height, Calibration.IDENTITY, realPatch, CalibrationProfile.Source.AUTO, 0L
        )
        assertTrue(real.contentEquals(codec.decode(codec.encode(real))!!))
    }

    @Test
    fun `损坏或版本不认识的档案读回为空`() {
        val codec = pngCodec()
        val patch = ArgbImage.filled(4, 4, BLACK)
        val text = codec.encode(
            CalibrationProfile.create(100, 100, Calibration.IDENTITY, patch, CalibrationProfile.Source.AUTO, 0L)
        )
        assertNull(codec.decode(""), "空文本")
        assertNull(codec.decode(text.replace("version=1", "version=99")), "版本不认识")
        assertNull(codec.decode(text.lines().filterNot { it.startsWith("scaleX=") }.joinToString("\n")), "缺字段")
        assertNull(codec.decode(text.replace(Regex("scaleX=.*"), "scaleX=abc")), "数字格式错")
        assertNull(codec.decode(text.replace(Regex("scaleX=.*"), "scaleX=-1.0")), "缩放非法")
        assertNull(codec.decode(text.replace("source=AUTO", "source=OTHER")), "来源不认识")
        assertNull(codec.decode(text.replace(Regex("toggleOnPatch=.*"), "toggleOnPatch=!!!")), "Base64 错")
        assertNull(codec.decode(text.replace(Regex("toggleOnPatch=.*"), "toggleOnPatch=AAAA")), "PNG 解不开")
    }

    @Test
    fun `档案点击位置换算到屏幕`() {
        val calibration = Calibration(0.5, 0.5, 10.0, 20.0)
        val profile = CalibrationProfile.create(
            1194, 540, calibration, ArgbImage.filled(2, 2, BLACK), CalibrationProfile.Source.AUTO, 0L
        )
        assertEquals(10.0 + 0.5 * ReferenceLayout.REFRESH_TAP_X, profile.tapScreenX())
        assertEquals(20.0 + 0.5 * ReferenceLayout.REFRESH_TAP_Y, profile.tapScreenY())
        assertTrue(profile.matchesScreen(1194, 540))
        assertFalse(profile.matchesScreen(540, 1194))
    }

    // ---- 横纵分开缩放的模板 ----

    @Test
    fun `横纵相同时模板与单一缩放逐像素一致`() {
        // 只传一个缩放与显式传两个相同缩放，结果必须一致，且槽位是正方形。
        // NOTE: 与改动前旧算法的一致性由 ScreenshotAnswerTest、CalibrationSyntheticTest 照旧通过来保证，
        // 这里比较的都是新代码。
        val database = TestFixtures.database
        for (scale in doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.33)) {
            val a = TemplateBank(database, TestFixtures::loadIcon, scale)
            val b = TemplateBank(database, TestFixtures::loadIcon, scale, scale)
            assertEquals(a.slotWidth, a.slotHeight)
            for ((id, t) in a.templates) {
                assertTrue(
                    t.template.image.pixels.contentEquals(b.templates.getValue(id).template.image.pixels),
                    "scale $scale ${t.factor.key} 模板不一致"
                )
            }
        }
    }

    @Test
    fun `横向拉伸的截图按吸附结果识别正确`() {
        val failures = mutableListOf<String>()
        for (label in TestFixtures.labels.filter { it.file in SOURCES }) {
            val source = TestFixtures.loadScreenshot(label.file)
            for ((sx, sy) in listOf(0.9 to 1.0, 1.1 to 1.0, 1.2 to 1.0)) {
                val synthetic = SyntheticFrames.scaleWithBorderXY(source, sx, sy, 40, 0)
                val result = snapper.snap(synthetic.image, UserFrameInput.fromCalibration(synthetic.truth))
                if (result == null) {
                    failures += "${label.file} ($sx, $sy)：吸附失败"
                    continue
                }
                val actual = TestFixtures.recognizerFactory.create(result.calibration).recognize(synthetic.image).slots
                for (i in actual.indices) {
                    if (actual[i] != label.expected[i]) {
                        failures += "${label.file} ($sx, $sy) 第 ${i + 1} 槽：期望 ${TestFixtures.describe(label.expected[i])}，" +
                            "实际 ${TestFixtures.describe(actual[i])}"
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // ---- 辅助 ----

    /** 4 个槽位共 16 条边界在 [actual] 与 [truth] 下的最大偏差（不取整）。 */
    private fun maxSlotError(actual: Calibration, truth: Calibration): Double =
        (1..ReferenceLayout.SLOT_COUNT).maxOf { slot ->
            val r = ReferenceLayout.slotRect(slot)
            val xs = doubleArrayOf(r.x.toDouble(), (r.x + r.width).toDouble())
            val ys = doubleArrayOf(r.y.toDouble(), (r.y + r.height).toDouble())
            maxOf(
                xs.maxOf { abs(actual.toScreenX(it) - truth.toScreenX(it)) },
                ys.maxOf { abs(actual.toScreenY(it) - truth.toScreenY(it)) }
            )
        }

    private fun writeReport(name: String, report: StringBuilder) {
        val out = File("build/reports/recognition/$name")
        out.parentFile.mkdirs()
        out.writeText(report.toString(), Charsets.UTF_8)
        println(report)
    }

    /** 测试用的 PNG 编解码（ImageIO），手机上换成 Bitmap.compress / BitmapFactory。 */
    private fun pngCodec() = CalibrationProfileCodec(
        encodePng = { img ->
            val buffered = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            buffered.setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width)
            ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
        },
        decodePng = { bytes ->
            val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("PNG 解码失败")
            ArgbImage(decoded.width, decoded.height, decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width))
        }
    )

    /** 一组合成条件。[jpegQuality] 为 null 时不重压缩。 */
    private class Case(
        val name: String,
        val scaleX: Double,
        val scaleY: Double,
        val padX: Int,
        val padY: Int,
        val jpegQuality: Float?,
        val mustPassExact: Boolean = false
    )

    private companion object {
        /** 验收 ① 的槽位误差上限（px）。 */
        const val MAX_SLOT_ERROR = 2.0

        /** 相邻槽位的间距（参考坐标），验收 ② 的偏移量。 */
        const val SLOT_PITCH = 163.0

        val BLACK = 0xFF000000.toInt()

        /** 开关关、开两种状态各取几张。 */
        val SOURCES = listOf("disable01.jpg", "enable01.jpg", "enable20.jpg", "enable40.jpg")

        /** 用户框误差（px），覆盖验收 ① 的 ±30。 */
        val USER_ERRORS = listOf(0, 15, -15, 30, -30)

        /** 等比缩放与横向拉伸，拉伸倍数取自验收 ①，条件参照调研报告实验 11 第 5 轮。 */
        val CASES = listOf(
            Case("原始 2388×1080", 1.0, 1.0, 0, 0, null, mustPassExact = true),
            Case("k = 1，JPEG 85", 1.0, 1.0, 0, 0, 0.85f),
            Case("k = 0.75，JPEG 85", 0.75, 0.75, 0, 0, 0.85f),
            Case("k = 1.25，黑边 (47, 0)", 1.25, 1.25, 47, 0, null),
            Case("kx = 1.1，ky = 1，JPEG 85", 1.1, 1.0, 0, 0, 0.85f),
            Case("kx = 0.9，ky = 1，黑边 (60, 0)", 0.9, 1.0, 60, 0, null),
            Case("kx = 1.2，ky = 1，黑边 (40, 0)", 1.2, 1.0, 40, 0, null)
        )
    }
}
