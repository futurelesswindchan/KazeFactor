package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import java.io.File
import kotlin.test.Test

/**
 * 第 2 类：阈值报告。不做断言，只统计 47 张截图上的距离分布，作为调整 [MatchThresholds] 的依据。
 *
 * 报告写入 `core-engine/build/reports/recognition/threshold-report.txt`，同时打印到标准输出。
 * 统计口径：
 * - 正确距离：答案因子在最优偏移处的距离，应 ≤ `factorMaxDistance`。
 * - 差距：最近的错误候选 − 正确距离，应 ≥ `minGap`，否则会进入二次比较。
 * - 问号距离：因子槽位与问号模板的距离，应 ≥ `questionMaxDistance`。
 */
class ThresholdReportTest {

    private class FactorSlotStat(
        val file: String,
        val slot: Int,
        val expectedId: Int,
        val correct: CandidateScore?,
        val nearestWrong: CandidateScore?,
        val questionDistance: Double,
        val result: SlotResult,
        val secondaryResolved: Boolean,
        val secondaryMargin: Double?
    ) {
        val gap: Double? get() = if (correct != null && nearestWrong != null) nearestWrong.distance - correct.distance else null
    }

    @Test
    fun `生成阈值报告`() {
        val recognizer = FactorRecognizer(TestFixtures.newMatcher())
        val factorStats = mutableListOf<FactorSlotStat>()
        val questionStats = mutableListOf<Pair<String, SlotAnalysis>>()

        for (label in TestFixtures.labels) {
            val analyses = recognizer.analyze(TestFixtures.loadScreenshot(label.file), fullAnalysis = true)
            analyses.forEachIndexed { i, a ->
                when (val expected = label.expected[i]) {
                    SlotResult.Question -> questionStats += label.file to a
                    is SlotResult.Factor -> factorStats += FactorSlotStat(
                        file = label.file,
                        slot = i + 1,
                        expectedId = expected.factorId,
                        correct = a.ranking.firstOrNull { it.factorId == expected.factorId },
                        nearestWrong = a.ranking.firstOrNull { it.factorId != expected.factorId },
                        questionDistance = a.questionDistance,
                        result = a.result,
                        secondaryResolved = a.secondaryResolved,
                        secondaryMargin = a.secondaryMargin
                    )
                    else -> error("答案里不应出现 $expected")
                }
            }
        }

        val report = buildString {
            appendLine("# 识别阈值报告（scale = 1，默认尺寸系数）")
            appendLine("当前阈值：${MatchThresholds()}")
            appendLine()

            val missing = factorStats.filter { it.correct == null }
            if (missing.isNotEmpty()) {
                appendLine("!! 答案因子不在该槽候选里（canAppearInSlot 与答案矛盾）：")
                missing.forEach { appendLine("   ${it.file} 第 ${it.slot} 槽 ${TestFixtures.keyOf(it.expectedId)}") }
                appendLine()
            }
            val valid = factorStats.filter { it.correct != null }

            appendLine("## 因子槽位（${valid.size} 个）")
            valid.maxByOrNull { it.correct!!.distance }?.let {
                appendLine("正确距离最大值：${fmt(it.correct!!.distance)}  ${loc(it)}")
            }
            valid.filter { it.gap != null }.minByOrNull { it.gap!! }?.let {
                appendLine("差距最小值：${fmt(it.gap!!)}  ${loc(it)} 最近错误候选 ${TestFixtures.keyOf(it.nearestWrong!!.factorId)}")
            }
            valid.minByOrNull { it.questionDistance }?.let {
                appendLine("因子槽位与问号的最小距离：${fmt(it.questionDistance)}  ${loc(it)}")
            }
            appendLine()

            appendLine("差距最小的 15 个槽位：")
            valid.filter { it.gap != null }.sortedBy { it.gap!! }.take(15).forEach {
                appendLine(
                    "  gap=${fmt(it.gap!!)}  正确=${fmt(it.correct!!.distance)}  " +
                        "错误=${fmt(it.nearestWrong!!.distance)}(${TestFixtures.keyOf(it.nearestWrong.factorId)})  " +
                        "${loc(it)}  判定=${TestFixtures.describe(it.result)}"
                )
            }
            appendLine()

            appendLine("各因子的正确距离（最大值 / 出现次数 / 最优偏移）：")
            valid.groupBy { it.expectedId }.toSortedMap().forEach { (id, stats) ->
                val worst = stats.maxByOrNull { it.correct!!.distance }!!
                appendLine(
                    "  ${TestFixtures.keyOf(id).padEnd(24)} max=${fmt(worst.correct!!.distance)}  n=${stats.size}  " +
                        "offset=(${worst.correct.offsetX}, ${worst.correct.offsetY})"
                )
            }
            appendLine()

            appendLine("## 问号槽位（${questionStats.size} 个）")
            questionStats.maxByOrNull { it.second.questionDistance }?.let { (file, a) ->
                appendLine("问号距离最大值：${fmt(a.questionDistance)}  $file 第 ${a.slotIndex} 槽")
            }
            questionStats.minByOrNull { it.second.ranking.first().distance }?.let { (file, a) ->
                val best = a.ranking.first()
                appendLine("问号槽位与因子的最小距离：${fmt(best.distance)}（${TestFixtures.keyOf(best.factorId)}）  $file 第 ${a.slotIndex} 槽")
            }

            appendLine()
            appendLine("经二次比较才分出结果的因子槽位：")
            factorStats.filter { it.secondaryResolved }.forEach {
                appendLine(
                    "  ${loc(it)}  判定=${TestFixtures.describe(it.result)}  " +
                        "二次优势=${it.secondaryMargin?.let(::fmt) ?: "-"}（阈值 ${MatchThresholds().secondaryMinGap}）"
                )
            }
            appendLine("仍为 Ambiguous 的因子槽位：")
            factorStats.filter { it.result is SlotResult.Ambiguous }.forEach {
                appendLine("  ${loc(it)}  判定=${TestFixtures.describe(it.result)}")
            }
        }

        val out = File("build/reports/recognition/threshold-report.txt")
        out.parentFile.mkdirs()
        out.writeText(report, Charsets.UTF_8)
        println(report)
    }

    private fun loc(s: FactorSlotStat) = "${s.file} 第 ${s.slot} 槽 ${TestFixtures.keyOf(s.expectedId)}"

    private fun fmt(v: Double) = "%.2f".format(v)
}
