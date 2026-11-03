package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.testutil.SyntheticFrame
import io.github.futurelesswindchan.kazefactor.engine.testutil.SyntheticFrames
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * 校准的合成分辨率测试（架构方案第 9 节第 3 步验收）。
 *
 * 目前覆盖第 1 段（帮助图标粗定位）：
 * - 验收 ②：真图标对应的候选，`s0` 误差按图标宽度分档，宽 ≥ 25 px 时 ≤ 3%，更小时 ≤ 6%。
 * - 验收 ④ 的前提：同屏 3 个帮助图标时，真图标（「挑战因子」标题旁）必须在候选列表里。
 *   最终「选中」它要靠第 2 段筛选，等第 2 段写完再断言。
 *
 * TODO(第 3a 步): 验收 ① 槽位误差 ≤ 2 px 且答案测试通过；③ 粗定位缩放偏 ±8% 时不得给出错误坐标；
 * ④ 校准最终选中真图标。
 *
 * 报告写入 `core-engine/build/reports/recognition/help-icon-locate-report.txt`，列出每帧的全部候选、
 * 真图标的排名和误差，供第 2 段和第 3b 步参考。
 */
class CalibrationSyntheticTest {

    private val calibrator = Calibrator(TestFixtures.helpIconTemplate)

    @Test
    fun `帮助图标粗定位：真图标在候选中且 s0 误差按图标宽度分档达标`() {
        val report = StringBuilder("# 帮助图标粗定位报告\n\n")
        report.appendLine("每帧列出全部候选（按距离升序）。「真」标记离真图标最近的候选。")
        report.appendLine("s0 误差 = s0 / 真实缩放 − 1；位置误差取横纵较大者（px）。")
        report.appendLine("分档：图标宽 ≥ $WIDE_ICON_PX px 时 s0 误差上限 ${pct(WIDE_MAX_ERROR)}，更小时 ${pct(NARROW_MAX_ERROR)}。")
        val failures = mutableListOf<String>()

        // 原始截图：47 张全部跑，真实换算就是 IDENTITY
        report.appendLine("\n## 原始截图（2388×1080）")
        for (label in TestFixtures.labels) {
            val frame = TestFixtures.loadScreenshot(label.file)
            check(report, failures, label.file, SyntheticFrame(frame, Calibration.IDENTITY))
        }

        // 合成分辨率：整屏缩放很耗时，只取问号、已刷新两组里的代表截图
        for (case in CASES) {
            report.appendLine("\n## ${case.name}")
            for (file in SYNTHETIC_SOURCES) {
                val source = TestFixtures.loadScreenshot(file)
                val synthetic = SyntheticFrames.scaleWithBorder(source, case.scale, case.padX, case.padY)
                val image = case.jpegQuality?.let { SyntheticFrames.jpegRecompress(synthetic.image, it) }
                    ?: synthetic.image
                check(report, failures, file, SyntheticFrame(image, synthetic.truth))
            }
        }

        val out = File("build/reports/recognition/help-icon-locate-report.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString(), Charsets.UTF_8)
        println(report)

        if (failures.isNotEmpty()) {
            fail("共 ${failures.size} 帧帮助图标粗定位不达标：\n" + failures.joinToString("\n"))
        }
    }

    /** 校验一帧并写报告，不达标的记入 [failures]。 */
    private fun check(report: StringBuilder, failures: MutableList<String>, name: String, frame: SyntheticFrame) {
        val candidates = calibrator.locateHelpIcon(frame.image)
        val truth = frame.truth
        // 真实缩放取横纵平均；等比缩放时两者只因取整差万分之几
        val trueScale = (truth.scaleX + truth.scaleY) / 2
        val trueX = truth.toScreenX(ReferenceLayout.HELP_ICON_RECT.x.toDouble())
        val trueY = truth.toScreenY(ReferenceLayout.HELP_ICON_RECT.y.toDouble())
        val iconWidth = ReferenceLayout.HELP_ICON_RECT.width * trueScale
        val maxScaleError = if (iconWidth >= WIDE_ICON_PX) WIDE_MAX_ERROR else NARROW_MAX_ERROR

        fun positionError(c: HelpIconEstimate) = maxOf(abs(c.x - trueX), abs(c.y - trueY))
        val nearest = candidates.minByOrNull(::positionError)
        val rank = nearest?.let { candidates.indexOf(it) + 1 }
        val scaleError = nearest?.let { it.scale / trueScale - 1 }
        val posError = nearest?.let(::positionError)
        val ok = nearest != null &&
            posError!! <= MAX_POSITION_ERROR &&
            abs(scaleError!!) <= maxScaleError

        report.appendLine(
            "  ${if (ok) "  " else "✘ "}${name.padEnd(14)} 图标宽 ${"%.1f".format(iconWidth)} px，" +
                "真图标排第 ${rank ?: "-"} / ${candidates.size}" +
                (nearest?.let {
                    "，s0 误差 ${"%+.2f".format(scaleError!! * 100)}%（上限 ${pct(maxScaleError)}），" +
                        "位置误差 ${"%.2f".format(posError!!)} px"
                } ?: "")
        )
        candidates.forEachIndexed { i, c ->
            val ref = "参考 (${"%.0f".format((c.x - truth.originX) / truth.scaleX)}, ${"%.0f".format((c.y - truth.originY) / truth.scaleY)})"
            report.appendLine(
                "      ${if (c === nearest) "真" else "  "} #${i + 1} 屏幕 (${c.x}, ${c.y}) $ref " +
                    "s0 ${"%.4f".format(c.scale)} 距离 ${"%.2f".format(c.distance)}"
            )
        }

        if (!ok) {
            failures += when {
                nearest == null -> "$name：没有任何候选"
                posError!! > MAX_POSITION_ERROR -> "$name：候选里没有真图标（最近的偏 ${"%.2f".format(posError)} px）"
                else -> "$name：s0 误差 ${"%+.2f".format(scaleError!! * 100)}% 超过 ${pct(maxScaleError)}"
            }
        }
    }

    private fun pct(v: Double) = "${"%.0f".format(v * 100)}%"

    /** 一组合成条件。[jpegQuality] 为 null 时不重压缩。 */
    private class Case(val name: String, val scale: Double, val padX: Int, val padY: Int, val jpegQuality: Float?)

    private companion object {
        /** 架构方案第 3.1 节第 1 段第 6 条：图标宽度达到这个值时用较严的精度要求。 */
        const val WIDE_ICON_PX = 25.0

        /** 图标宽 ≥ [WIDE_ICON_PX] 时的 `s0` 误差上限。 */
        const val WIDE_MAX_ERROR = 0.03

        /** 图标宽 < [WIDE_ICON_PX] 时的 `s0` 误差上限，第 2 段窗口系数 0.06 与之对应。 */
        const val NARROW_MAX_ERROR = 0.06

        /**
         * 判定「这个候选就是真图标」的位置误差上限。
         * 实验 8、12 都按「帮助图标位置误差 ±2 px」验证过第 2 段，这里用同样的值。
         */
        const val MAX_POSITION_ERROR = 2.0

        /** 问号截图和已刷新截图各取几张，覆盖两种开关状态。 */
        val SYNTHETIC_SOURCES = listOf("disable01.jpg", "enable01.jpg", "enable20.jpg", "enable40.jpg")

        /** 验收 ① 的 4 种缩放，各带不同的黑边和 JPEG 质量。 */
        val CASES = listOf(
            Case("缩放 0.5，无黑边，JPEG 85", 0.5, 0, 0, 0.85f),
            Case("缩放 0.75，黑边 (30, 30)，JPEG 75", 0.75, 30, 30, 0.75f),
            Case("缩放 1.25，黑边 (47, 0)", 1.25, 47, 0, null),
            Case("缩放 1.33，黑边 (0, 40)，JPEG 80", 1.33, 0, 40, 0.80f)
        )
    }
}
