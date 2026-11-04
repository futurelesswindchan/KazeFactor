package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
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
 * 每帧只做一次整屏粗定位（约 0.3 秒），然后在同一批候选上依次检查：
 * - 验收 ②：真图标对应的候选，`s0` 误差按图标宽度分档，宽 ≥ 25 px 时 ≤ 3%，更小时 ≤ 6%。
 * - 验收 ①（位置部分）：完整校准成功，4 个槽位的边界相对真值误差 ≤ 2 px。
 * - 验收 ③：把真图标候选的 `s0` 人为偏 ±8% 后精修，要么被拒，要么槽位误差 ≤ 2 px。
 * - 验收 ④：校准所依据的候选必须是「挑战因子」标题旁的帮助图标。
 *
 * TODO(第 3a 步): 验收 ① 的「答案测试仍通过」，等 `FactorRecognizer` 接入 `Calibration` 后补上。
 *
 * 报告写入 `core-engine/build/reports/recognition/calibration-report.txt`。
 */
class CalibrationSyntheticTest {

    private val calibrator = Calibrator(TestFixtures.helpIconTemplate)

    @Test
    fun `帮助图标粗定位与面板边框精修达到验收标准`() {
        val report = StringBuilder("# 自动校准报告\n\n")
        report.appendLine("s0 误差 = s0 / 真实缩放 − 1；位置误差取横纵较大者（px）。")
        report.appendLine("分档：图标宽 ≥ $WIDE_ICON_PX px 时 s0 误差上限 ${pct(WIDE_MAX_ERROR)}，更小时 ${pct(NARROW_MAX_ERROR)}。")
        report.appendLine("槽位误差：4 个槽位共 16 条边界相对真值的最大偏差（px），上限 $MAX_SLOT_ERROR。")
        report.appendLine("残差：横向为 4 条竖边拟合的最大残差，纵向为 3 条横边的最大残差，上限 ${PanelEdgeRefiner.MAX_RESIDUAL}。")
        report.appendLine("扰动：真图标候选的 s0 乘以 ${1 + PERTURBATION} / ${1 - PERTURBATION} 后单独精修。")
        val failures = mutableListOf<String>()

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

        val out = File("build/reports/recognition/calibration-report.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString(), Charsets.UTF_8)
        println(report)

        if (failures.isNotEmpty()) {
            fail("共 ${failures.size} 项不达标：\n" + failures.joinToString("\n"))
        }
    }

    /** 对一帧做全部检查并写报告，不达标的记入 [failures]。 */
    private fun check(report: StringBuilder, failures: MutableList<String>, name: String, frame: SyntheticFrame) {
        val truth = frame.truth
        val candidates = calibrator.locateHelpIcon(frame.image)
        report.appendLine("  $name")

        // ---- 验收 ②：粗定位 ----
        val trueScale = (truth.scaleX + truth.scaleY) / 2
        val iconWidth = ReferenceLayout.HELP_ICON_RECT.width * trueScale
        val maxScaleError = if (iconWidth >= WIDE_ICON_PX) WIDE_MAX_ERROR else NARROW_MAX_ERROR
        val trueIcon = candidates.minByOrNull { iconPositionError(it, truth) }
        if (trueIcon == null || iconPositionError(trueIcon, truth) > MAX_ICON_POSITION_ERROR) {
            report.appendLine("    ✘ 粗定位：候选里没有真图标（共 ${candidates.size} 个候选）")
            failures += "$name：粗定位候选里没有真图标"
        } else {
            val scaleError = trueIcon.scale / trueScale - 1
            val ok = abs(scaleError) <= maxScaleError
            report.appendLine(
                "    ${mark(ok)}粗定位：图标宽 ${"%.1f".format(iconWidth)} px，真图标排第 ${candidates.indexOf(trueIcon) + 1} / ${candidates.size}，" +
                    "s0 误差 ${signedPct(scaleError)}（上限 ${pct(maxScaleError)}），位置误差 ${"%.2f".format(iconPositionError(trueIcon, truth))} px"
            )
            if (!ok) failures += "$name：s0 误差 ${signedPct(scaleError)} 超过 ${pct(maxScaleError)}"
        }

        // ---- 验收 ①④：完整校准 ----
        val result = calibrator.refineCandidates(frame.image, candidates)
        if (result == null) {
            report.appendLine("    ✘ 精修：全部 ${candidates.size} 个候选都被拒收")
            failures += "$name：校准失败（全部候选被拒收）"
        } else {
            val slotError = maxSlotError(result.calibration, truth)
            val pickedTrue = iconPositionError(result.source, truth) <= MAX_ICON_POSITION_ERROR
            val ok = slotError <= MAX_SLOT_ERROR && pickedTrue
            report.appendLine(
                "    ${mark(ok)}精修：用第 ${candidates.indexOf(result.source) + 1} 个候选" +
                    (if (pickedTrue) "（真图标）" else "（**不是真图标**）") +
                    "，scale ${"%.5f".format(result.calibration.scaleX)}（真值 ${"%.5f".format(trueScale)}），" +
                    "残差 横 ${"%.2f".format(result.horizontalResidual)} / 纵 ${"%.2f".format(result.verticalResidual)}，" +
                    "槽位误差 ${"%.2f".format(slotError)} px"
            )
            if (!pickedTrue) failures += "$name：校准选中了假帮助图标（验收 ④）"
            if (slotError > MAX_SLOT_ERROR) failures += "$name：槽位误差 ${"%.2f".format(slotError)} px 超过 $MAX_SLOT_ERROR（验收 ①）"
        }
        // 被拒收的候选也列出原因，方便核对假锚点是怎么被筛掉的
        appendRejections(report, frame.image, candidates, result?.source)

        // ---- 验收 ③：人为扰动 s0 ----
        if (trueIcon != null && iconPositionError(trueIcon, truth) <= MAX_ICON_POSITION_ERROR) {
            for (sign in doubleArrayOf(1.0, -1.0)) {
                val perturbed = trueIcon.copy(scale = trueIcon.scale * (1 + sign * PERTURBATION))
                val r = calibrator.refineCandidates(frame.image, listOf(perturbed))
                val label = "s0 ${if (sign > 0) "+" else "−"}${pct(PERTURBATION)}"
                if (r == null) {
                    report.appendLine("      扰动 $label：被拒收")
                } else {
                    val err = maxSlotError(r.calibration, truth)
                    val ok = err <= MAX_SLOT_ERROR
                    report.appendLine("    ${mark(ok)}  扰动 $label：通过检查，槽位误差 ${"%.2f".format(err)} px")
                    if (!ok) failures += "$name：扰动 $label 后给出了错误坐标，槽位误差 ${"%.2f".format(err)} px（验收 ③）"
                }
            }
        }
    }

    /** 列出没被采用的候选为什么被拒。 */
    private fun appendRejections(
        report: StringBuilder,
        image: ArgbImage,
        candidates: List<HelpIconEstimate>,
        accepted: HelpIconEstimate?
    ) {
        val refiner = PanelEdgeRefiner()
        candidates.forEachIndexed { i, c ->
            if (c === accepted) return@forEachIndexed
            val r = refiner.refine(image, c)
            val reason = when {
                r == null -> "找不到边或边落在画面外"
                !r.passesResidualCheck ->
                    "残差超标（横 ${"%.2f".format(r.horizontalResidual)} / 纵 ${"%.2f".format(r.verticalResidual)}）"
                else -> "可以通过，但排在已采用的候选之后"
            }
            report.appendLine("        #${i + 1} (${c.x}, ${c.y}) 距离 ${"%.2f".format(c.distance)}：$reason")
        }
    }

    /** 候选与真图标左上角的距离（横纵较大者）。 */
    private fun iconPositionError(c: HelpIconEstimate, truth: Calibration): Double {
        val r = ReferenceLayout.HELP_ICON_RECT
        return maxOf(abs(c.x - truth.toScreenX(r.x.toDouble())), abs(c.y - truth.toScreenY(r.y.toDouble())))
    }

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

    private fun mark(ok: Boolean) = if (ok) "  " else "✘ "
    private fun pct(v: Double) = "${"%.0f".format(v * 100)}%"
    private fun signedPct(v: Double) = "${"%+.2f".format(v * 100)}%"

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
        const val MAX_ICON_POSITION_ERROR = 2.0

        /** 验收 ①③ 的槽位误差上限（px）。 */
        const val MAX_SLOT_ERROR = 2.0

        /** 验收 ③ 的 `s0` 扰动幅度。 */
        const val PERTURBATION = 0.08

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
