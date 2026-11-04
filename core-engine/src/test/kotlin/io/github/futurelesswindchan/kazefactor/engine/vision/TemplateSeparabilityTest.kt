package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.image.alphaOf
import io.github.futurelesswindchan.kazefactor.engine.image.rgbAbsDiffSum
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 第 3 类：模板可分性。不依赖截图，所以也覆盖了截图里从没出现过的 9 个因子。
 *
 * 做法：把每个候选模板合成到纯色背景上，交给 [SlotMatcher] 识别，结果必须包含正确答案
 * （唯一确定为它，或在 Ambiguous 候选集合里）。被判为 Ambiguous 的组合写进报告，
 * 用来确认易混图标是否只有「天赋」系列。另附同槽模板两两距离最小的若干对。
 *
 * 报告写入 `core-engine/build/reports/recognition/separability-report.txt`。
 */
class TemplateSeparabilityTest {

    private val bank = TestFixtures.templateBank
    private val matcher = TestFixtures.newMatcher()

    @Test
    fun `各槽候选数与架构方案一致`() {
        assertEquals(
            listOf(28, 37, 39, 16),
            (1..ReferenceLayout.SLOT_COUNT).map { bank.candidatesForSlot(it).size }
        )
        assertEquals(59, bank.templates.size)
    }

    @Test
    fun `合成到纯色背景上的每个模板都能被识别为自己`() {
        val failures = mutableListOf<String>()
        val report = StringBuilder("# 模板可分性报告（scale = 1）\n\n")

        for ((bgName, bg) in BACKGROUNDS) {
            report.appendLine("## 背景 $bgName")
            for (slot in 1..ReferenceLayout.SLOT_COUNT) {
                for (candidate in bank.candidatesForSlot(slot)) {
                    val region = synthesize(candidate.template.image, bg)
                    val analysis = matcher.match(region, slot)
                    val id = candidate.factor.id
                    val ok = when (val r = analysis.result) {
                        is SlotResult.Factor -> r.factorId == id
                        is SlotResult.Ambiguous -> id in r.candidateIds
                        else -> false
                    }
                    if (!ok) {
                        failures += "背景 $bgName 第 $slot 槽 ${candidate.factor.key} → ${TestFixtures.describe(analysis.result)}"
                    }
                    if (analysis.result !is SlotResult.Factor || analysis.secondaryResolved) {
                        val second = analysis.ranking.getOrNull(1)
                        report.appendLine(
                            "  第 $slot 槽 ${candidate.factor.key.padEnd(24)} → ${TestFixtures.describe(analysis.result)}" +
                                (if (analysis.secondaryResolved) "（二次比较，优势 ${fmt(analysis.secondaryMargin!!)}）" else "") +
                                (second?.let { "  次优 ${TestFixtures.keyOf(it.factorId)} ${fmt(it.distance)}" } ?: "")
                        )
                    }
                }
            }
            report.appendLine()
        }

        report.appendLine("## 同槽模板两两距离最小的 15 对（只比较两者都不透明的像素）")
        pairwiseDistances().sortedBy { it.third }.take(15).forEach { (a, b, d) ->
            report.appendLine("  ${fmt(d)}  ${TestFixtures.keyOf(a)} / ${TestFixtures.keyOf(b)}")
        }

        val out = File("build/reports/recognition/separability-report.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString(), Charsets.UTF_8)
        println(report)

        if (failures.isNotEmpty()) {
            fail("共 ${failures.size} 个模板没能识别为自己：\n" + failures.joinToString("\n"))
        }
    }

    /** 模板贴到 [background] 色的搜索区域正中（偏移 0）。 */
    private fun synthesize(template: ArgbImage, background: Int): ArgbImage {
        val width = matcher.regionWidth
        val height = matcher.regionHeight
        return ImageOps.drawOver(
            ArgbImage.filled(width, height, background), template,
            (width - template.width) / 2, (height - template.height) / 2
        )
    }

    /** 同一槽位内出现过的每一对模板，在共同不透明像素上的平均通道差。 */
    private fun pairwiseDistances(): List<Triple<Int, Int, Double>> {
        val pairs = mutableSetOf<Pair<Int, Int>>()
        for (slot in 1..ReferenceLayout.SLOT_COUNT) {
            val ids = bank.candidatesForSlot(slot).map { it.factor.id }
            for (i in ids.indices) for (j in i + 1 until ids.size) pairs += ids[i] to ids[j]
        }
        return pairs.map { (a, b) ->
            val ia = bank.templates.getValue(a).template.image
            val ib = bank.templates.getValue(b).template.image
            var sum = 0L
            var count = 0
            for (k in ia.pixels.indices) {
                val pa = ia.pixels[k]
                val pb = ib.pixels[k]
                if (alphaOf(pa) >= TemplateBank.MASK_ALPHA && alphaOf(pb) >= TemplateBank.MASK_ALPHA) {
                    sum += rgbAbsDiffSum(pa, pb)
                    count++
                }
            }
            Triple(a, b, if (count == 0) Double.MAX_VALUE else sum.toDouble() / (count * 3))
        }
    }

    private fun fmt(v: Double) = "%.2f".format(v)

    private companion object {
        /** 两种极端背景：黑色和中灰。真实槽位背景的影响由截图答案测试覆盖。 */
        val BACKGROUNDS = listOf(
            "黑色" to 0xFF000000.toInt(),
            "中灰" to 0xFF808080.toInt()
        )
    }
}
