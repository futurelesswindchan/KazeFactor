package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.testutil.ScreenshotLabel
import io.github.futurelesswindchan.kazefactor.engine.testutil.SyntheticFrames
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 跨机型屏幕形态与挖孔避让矩阵全自动合成测试。
 *
 * 基于 4 张基准实机截图（`disable01.jpg`、`enable01.jpg`、`enable20.jpg`、`enable40.jpg`），
 * 在内存中动态模拟主流 Android 设备横屏物理分辨率（720p、1080p、1.5K、2K）及单侧挖孔避让偏移，
 * 验证以下核心指标：
 * 1. 自动校准不落空：[Calibrator.calibrate] 返回非空等比 [Calibration]；
 * 2. 槽位几何精度：校准得出的 4 个槽位边界与真值之间的最大偏差不超过 [MAX_SLOT_ERROR_PX]（1.5 px）；
 * 3. 识别 100% 准确：由校准结果构建的 [FactorRecognizer] 在 4 个槽位上均不出现 [SlotResult.Unknown]，且与基准答案完全一致；
 * 4. 极端遮挡兜底与高分屏手动微调吸附：面板竖边受遮挡时居中几何兜底生效，高分屏初始框吸附不发生缩水偏移。
 */
class DeviceMatrixSyntheticTest {

    private val factory = TestFixtures.recognizerFactory
    private val calibrator = Calibrator(TestFixtures.helpIconTemplate, factory)
    private val snapper = Snapper()

    /**
     * 委托调用 [SyntheticFrames.synthesizeDeviceFrame] 生成指定设备分辨率与挖孔避让偏移的内存合成帧。
     */
    fun synthesizeDeviceFrame(
        source: ArgbImage,
        targetWidth: Int,
        targetHeight: Int,
        notchOffsetLeft: Int = 0
    ): Pair<ArgbImage, Calibration> =
        SyntheticFrames.synthesizeDeviceFrame(source, targetWidth, targetHeight, notchOffsetLeft)

    @Test
    fun `主流设备分辨率与挖孔避让矩阵下自动校准与槽位识别全部达标`() {
        val labelsByFile = TestFixtures.labels.associateBy { it.file }
        val sources = BENCHMARK_FILES.associateWith { TestFixtures.loadScreenshot(it) }
        val report = StringBuilder("# 跨机型矩阵合成测试报告\n\n")
        report.appendLine("基准截图：${BENCHMARK_FILES.joinToString(", ")}")
        report.appendLine("断言标准：自动校准非空、4 槽位边界最大误差 ≤ $MAX_SLOT_ERROR_PX px、4 槽位识别无 Unknown 且 100% 匹配标签。\n")

        val failures = mutableListOf<String>()
        val evalCache = HashMap<EvalKey, EvalOutcome>()

        for (spec in DEVICE_MATRIX) {
            for (notchOffset in spec.notchOffsets) {
                val sectionTitle = "${spec.name} (${spec.width}×${spec.height}, notchOffset=$notchOffset)"
                report.appendLine("## $sectionTitle")
                for (fileName in BENCHMARK_FILES) {
                    val label = labelsByFile.getValue(fileName)
                    val source = sources.getValue(fileName)
                    val key = EvalKey(fileName, spec.width, spec.height, notchOffset)
                    val outcome = evalCache.getOrPut(key) {
                        evaluateSyntheticFrame(source, label, spec.width, spec.height, notchOffset)
                    }
                    report.appendLine("  ${if (outcome.errorMessage == null) "  " else "✘ "}$fileName -> ${outcome.summary}")
                    if (outcome.errorMessage != null) {
                        failures += "[$sectionTitle] $fileName：${outcome.errorMessage}"
                    }
                }
                report.appendLine()
            }
        }

        val out = File("build/reports/recognition/device-matrix-report.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString(), Charsets.UTF_8)
        println(report)

        if (failures.isNotEmpty()) {
            fail("跨机型矩阵共 ${failures.size} 项未达标：\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `高分屏挖孔避让且面板边框受遮挡时通过居中几何兜底完成校准与识别`() {
        val label = TestFixtures.labels.first { it.file == "enable01.jpg" }
        val rawSource = TestFixtures.loadScreenshot(label.file)
        // 遮蔽参考截图左侧面板竖边区域（x = 950..1020，y = 180..700），使面板边框精修无法找到竖边跳变
        val occludedPixels = rawSource.pixels.copyOf()
        for (y in 180..700) {
            val rowBase = y * rawSource.width
            for (x in 950..1020) {
                occludedPixels[rowBase + x] = 0xFF202020.toInt()
            }
        }
        val occludedSource = ArgbImage(rawSource.width, rawSource.height, occludedPixels)

        val fallbackCases = listOf(
            Triple("HUAWEI Mate 60 Pro", 2720 to 1260, 65),
            Triple("HUAWEI Mate 40 Pro", 2772 to 1344, 80)
        )

        for ((deviceName, size, notchOffset) in fallbackCases) {
            val (width, height) = size
            val (mockScreenshot, truth) = synthesizeDeviceFrame(occludedSource, width, height, notchOffset)

            // 确认精修路径被拒收，迫使走居中几何兜底
            val candidates = calibrator.locateHelpIcon(mockScreenshot)
            assertNull(
                calibrator.refineCandidates(mockScreenshot, candidates),
                "$deviceName 遮挡面板竖边后 refineCandidates 应返回 null"
            )

            // 确认完整校准通过居中几何兜底成功返回
            val fallbackCalib = assertNotNull(
                calibrator.calibrate(mockScreenshot),
                "$deviceName 居中几何兜底校准不应返回 null"
            )
            assertEquals(truth.scaleX, fallbackCalib.scaleX, 1e-6)
            assertEquals(truth.scaleY, fallbackCalib.scaleY, 1e-6)
            // SlotMatcher 内部包含 ±SEARCH_MARGIN (6 px) 的逐像素精修窗口，配合 4 px 兜底扫描步长，首次命中零损平台区的偏移在 8 px 内
            assertEquals(
                truth.originX,
                fallbackCalib.originX,
                (ReferenceLayout.SEARCH_MARGIN + 2).toDouble(),
                "$deviceName 兜底横向偏移应在搜索边距与步长容差范围内"
            )
            assertEquals(0.0, fallbackCalib.originY, 1e-6)

            // 确认兜底校准参数可 100% 正确识别 4 个槽位
            val recognized = factory.create(fallbackCalib).recognize(mockScreenshot).slots
            assertTrue(recognized.none { it == SlotResult.Unknown }, "$deviceName 兜底识别结果不应包含 Unknown")
            assertEquals(label.expected, recognized, "$deviceName 兜底识别因子应与基准答案一致")
        }
    }

    @Test
    fun `高分屏手动引导初始居中框与微调范围内吸附保持等比且不劣化`() {
        val label = TestFixtures.labels.first { it.file == "enable01.jpg" }
        val source = TestFixtures.loadScreenshot(label.file)
        val (frame, truth) = synthesizeDeviceFrame(source, targetWidth = 2720, targetHeight = 1260, notchOffsetLeft = 0)
        val initialInput = UserFrameInput.fromCalibration(truth)

        // 1. 初始居中框与微调偏差（±12 px）下吸附：缩放保持等比且不偏离 1.1667，槽位误差 ≤ 1.5 px
        for (delta in listOf(0.0, 12.0, -12.0)) {
            val perturbed = UserFrameInput(
                left = initialInput.left + delta,
                right = initialInput.right - delta,
                top = initialInput.top + delta
            )
            val measured = snapper.measure(frame, perturbed)
            assertTrue(measured.passesEdgeCheck, "delta=$delta 时应通过逐边覆盖率检查（worst=${measured.worstCoverage}）")
            assertEquals(measured.calibration.scaleX, measured.calibration.scaleY, 1e-9)
            val slotErr = maxSlotBoundaryError(measured.calibration, truth)
            assertTrue(slotErr <= MAX_SLOT_ERROR_PX, "delta=$delta 时槽位边界误差 $slotErr px 超过 $MAX_SLOT_ERROR_PX px")

            val slots = factory.create(measured.calibration).recognize(frame).slots
            assertEquals(label.expected, slots)
        }

        // 2. 初始框严重偏离（偏移 1 个槽位间距）时，逐边覆盖率检查必须拦下，防止用次品解覆盖用户框
        val shiftedInput = UserFrameInput(
            left = initialInput.left + 163.0 * truth.scaleX,
            right = initialInput.right + 163.0 * truth.scaleX,
            top = initialInput.top
        )
        val badMeasured = snapper.measure(frame, shiftedInput)
        assertFalse(badMeasured.passesEdgeCheck, "偏离槽位时 passesEdgeCheck 必须为 false")
        assertNull(snapper.snap(frame, shiftedInput))
    }

    /**
     * 对单张合成设备画面执行校准、槽位精度与因子识别校验。
     */
    private fun evaluateSyntheticFrame(
        source: ArgbImage,
        label: ScreenshotLabel,
        targetWidth: Int,
        targetHeight: Int,
        notchOffsetLeft: Int
    ): EvalOutcome {
        val (mockScreenshot, truth) = synthesizeDeviceFrame(source, targetWidth, targetHeight, notchOffsetLeft)
        val actual = calibrator.calibrate(mockScreenshot)
            ?: return EvalOutcome(
                summary = "校准失败（返回 null）",
                errorMessage = "calibrator.calibrate 返回 null"
            )

        val boundaryError = maxSlotBoundaryError(actual, truth)
        val rectError = maxSlotRectBoundaryError(actual, truth)
        val slots = factory.create(actual).recognize(mockScreenshot).slots
        val hasUnknown = slots.any { it == SlotResult.Unknown }
        val wrongIndices = slots.indices.filter { slots[it] != label.expected[it] }

        val summary = "scale=${"%.4f".format(actual.scaleX)} (truth=${"%.4f".format(truth.scaleX)}), " +
            "originX=${"%.2f".format(actual.originX)} (truth=${"%.2f".format(truth.originX)}), " +
            "槽位误差=${"%.2f".format(boundaryError)} px (取整矩形误差=$rectError px), " +
            if (wrongIndices.isEmpty()) "4 槽全对" else "识别错误槽位=${wrongIndices.map { it + 1 }}"

        val error = when {
            boundaryError > MAX_SLOT_ERROR_PX ->
                "槽位边界偏差 ${"%.2f".format(boundaryError)} px 超过上限 $MAX_SLOT_ERROR_PX px"
            rectError > MAX_SLOT_ERROR_PX ->
                "槽位屏幕矩形 (toScreenRect) 边界偏差 $rectError px 超过上限 $MAX_SLOT_ERROR_PX px"
            hasUnknown ->
                "识别结果出现 SlotResult.Unknown：${slots.map(TestFixtures::describe)}"
            wrongIndices.isNotEmpty() -> {
                val detail = wrongIndices.joinToString("；") { idx ->
                    "第 ${idx + 1} 槽期望 ${TestFixtures.describe(label.expected[idx])}，实际 ${TestFixtures.describe(slots[idx])}"
                }
                "识别结果与基准标签不一致（$detail）"
            }
            else -> null
        }
        return EvalOutcome(summary, error)
    }

    /**
     * 计算 4 个槽位共 16 条浮点屏幕边界在 [actual] 与 [truth] 下的最大绝对偏差（单位：px）。
     */
    private fun maxSlotBoundaryError(actual: Calibration, truth: Calibration): Double =
        (1..ReferenceLayout.SLOT_COUNT).maxOf { slot ->
            val r = ReferenceLayout.slotRect(slot)
            val xs = doubleArrayOf(r.x.toDouble(), (r.x + r.width).toDouble())
            val ys = doubleArrayOf(r.y.toDouble(), (r.y + r.height).toDouble())
            maxOf(
                xs.maxOf { abs(actual.toScreenX(it) - truth.toScreenX(it)) },
                ys.maxOf { abs(actual.toScreenY(it) - truth.toScreenY(it)) }
            )
        }

    /**
     * 计算 4 个槽位经 [Calibration.toScreenRect] 换算后的整数像素矩形边界与真值矩形边界的最大偏差（单位：px）。
     */
    private fun maxSlotRectBoundaryError(actual: Calibration, truth: Calibration): Int =
        (1..ReferenceLayout.SLOT_COUNT).maxOf { slot ->
            val ref = ReferenceLayout.slotRect(slot)
            val a = actual.toScreenRect(ref)
            val t = truth.toScreenRect(ref)
            maxOf(
                abs(a.x - t.x),
                abs((a.x + a.width) - (t.x + t.width)),
                abs(a.y - t.y),
                abs((a.y + a.height) - (t.y + t.height))
            )
        }

    /** 设备屏幕形态测试规格。 */
    private data class DeviceSpec(
        val name: String,
        val width: Int,
        val height: Int,
        val notchOffsets: List<Int>
    )

    private data class EvalKey(
        val fileName: String,
        val width: Int,
        val height: Int,
        val notchOffset: Int
    )

    private data class EvalOutcome(
        val summary: String,
        val errorMessage: String?
    )

    private companion object {
        /** 槽位边界相对真值的最大允许误差（单位：px）。 */
        const val MAX_SLOT_ERROR_PX = 1.5

        /** 覆盖开关关闭（问号）与开关开启（已刷新因子）的 4 张基准实机截图。 */
        val BENCHMARK_FILES = listOf("disable01.jpg", "enable01.jpg", "enable20.jpg", "enable40.jpg")

        /** 主流 Android 设备横屏分辨率与挖孔避让参数矩阵。 */
        val DEVICE_MATRIX = listOf(
            DeviceSpec("标准参考机", 2388, 1080, listOf(0)),
            DeviceSpec("HUAWEI Mate 60 Pro", 2720, 1260, listOf(0, 65)),
            DeviceSpec("HUAWEI Mate 40 Pro", 2772, 1344, listOf(0, 80)),
            DeviceSpec("HUAWEI P50 / P60", 2700, 1224, listOf(0)),
            DeviceSpec("Xiaomi 14", 2670, 1200, listOf(0)),
            DeviceSpec("Xiaomi 12/13/14 Pro / vivo X80/X90 Pro+", 3200, 1440, listOf(0, 50)),
            DeviceSpec("vivo X100 Pro", 2800, 1260, listOf(0)),
            DeviceSpec("OPPO Find X7", 2780, 1264, listOf(0)),
            DeviceSpec("OnePlus 10/11/12 (3216×1440)", 3216, 1440, listOf(0, 60)),
            DeviceSpec("OnePlus 10/11/12 (3168×1440)", 3168, 1440, listOf(0, 60)),
            DeviceSpec("Samsung Galaxy S21/S22/S23/S24 (2400×1080)", 2400, 1080, listOf(0)),
            DeviceSpec("Samsung Galaxy S21/S22/S23/S24 (2340×1080)", 2340, 1080, listOf(0)),
            DeviceSpec("Samsung Galaxy S22 Ultra (3088×1440)", 3088, 1440, listOf(0)),
            DeviceSpec("Samsung Galaxy S24 Ultra (3120×1440)", 3120, 1440, listOf(0)),
            DeviceSpec("Google Pixel 6/7/8", 2400, 1080, listOf(0)),
            DeviceSpec("Google Pixel 6 Pro", 3120, 1440, listOf(0)),
            DeviceSpec("入门档千元机 (720p)", 1600, 720, listOf(0))
        )
    }
}
