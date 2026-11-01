package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.rgbAbsDiffSum

/**
 * 槽位判定用的阈值，距离单位均为「掩膜内 RGB 三通道平均绝对差」（0~255）。
 *
 * 默认值是架构方案第 3.2 节的初值，须按 `ThresholdReportTest` 的报告调整后回写架构方案。
 *
 * @property questionMaxDistance 与问号模板的距离低于此值判为问号
 * @property factorMaxDistance 最优因子距离不超过此值才算匹配到因子
 * @property minGap 次优 − 最优至少达到此值才唯一确定，否则进入二次比较
 * @property secondaryPixelDiff 二次比较时，两张模板同一像素的平均通道差超过此值才算「差异像素」
 * @property secondaryMinGap 二次比较中两者距离至少相差此值才分出胜负
 * @property secondaryMinPixels 差异像素少于此数时不做二次比较，直接视为分不开
 */
data class MatchThresholds(
    val questionMaxDistance: Double = 10.0,
    val factorMaxDistance: Double = 30.0,
    val minGap: Double = 8.0,
    val secondaryPixelDiff: Int = 40,
    val secondaryMinGap: Double = 8.0,
    val secondaryMinPixels: Int = 30
)

/**
 * 某个候选在最优偏移处的距离。
 *
 * @property factorId 因子 id
 * @property distance 最优偏移处的距离
 * @property offsetX 最优偏移（相对槽位标准位置，像素）
 * @property offsetY 同上
 */
data class CandidateScore(val factorId: Int, val distance: Double, val offsetX: Int, val offsetY: Int)

/**
 * 单个槽位的完整匹配过程，供阈值报告和调试使用。
 *
 * @property slotIndex 槽位序号，1~4
 * @property result 最终判定
 * @property questionDistance 与该槽问号模板的距离
 * @property ranking 按距离升序的候选列表；判为问号且未要求完整分析时为空
 * @property secondaryResolved 是否经过二次比较才唯一确定
 * @property secondaryMargin 二次比较中胜者对各对手的最小距离优势；未经二次比较或没分出胜负时为 null
 */
data class SlotAnalysis(
    val slotIndex: Int,
    val result: SlotResult,
    val questionDistance: Double,
    val ranking: List<CandidateScore>,
    val secondaryResolved: Boolean = false,
    val secondaryMargin: Double? = null
)

/**
 * 带透明掩膜的像素差模板匹配（架构方案第 3.2 节）。
 *
 * 流程：
 * 1. 先比问号模板，距离低于阈值直接判为问号，跳过因子比较。
 * 2. 该槽全部候选在 ±2 px（步长 2）内粗搜，距离最小的 3 个再在 ±3 px（步长 1）内精修。
 * 3. 最优与次优差距不足时，在差异像素上做二次比较，仍分不开就返回 [SlotResult.Ambiguous]。
 *
 * NOTE: 本类不可变，可在多个协程间共享。
 *
 * @param bank 因子模板库，提供每个槽位的候选
 * @param questionTemplates 第 1~4 槽的问号模板，边长必须等于 [TemplateBank.slotSize]
 * @param thresholds 判定阈值
 */
class SlotMatcher(
    private val bank: TemplateBank,
    private val questionTemplates: List<MaskedTemplate>,
    private val thresholds: MatchThresholds = MatchThresholds()
) {
    init {
        require(questionTemplates.size == ReferenceLayout.SLOT_COUNT) {
            "需要 ${ReferenceLayout.SLOT_COUNT} 个问号模板，实际 ${questionTemplates.size} 个"
        }
        questionTemplates.forEachIndexed { i, t ->
            require(t.image.width == bank.slotSize && t.image.height == bank.slotSize) {
                "第 ${i + 1} 槽问号模板尺寸 ${t.image.width}x${t.image.height} 与槽位 ${bank.slotSize} 不符"
            }
        }
    }

    /** 搜索区域的边长：槽位四边各多取 [ReferenceLayout.SEARCH_MARGIN]。 */
    val regionSize: Int get() = bank.slotSize + 2 * MARGIN

    /**
     * 识别一个槽位。
     *
     * @param region 以槽位为中心、边长为 [regionSize] 的截图区域
     * @param slotIndex 槽位序号，1~4
     * @param fullAnalysis 为 true 时即使判为问号也计算全部候选的距离（阈值报告用，较慢）
     * @throws IllegalArgumentException 区域尺寸不符或序号越界时
     */
    fun match(region: ArgbImage, slotIndex: Int, fullAnalysis: Boolean = false): SlotAnalysis {
        require(region.width == regionSize && region.height == regionSize) {
            "搜索区域应为 ${regionSize}x$regionSize，实际 ${region.width}x${region.height}"
        }
        val questionDistance = bestOffset(questionTemplates[slotIndex - 1], region, FINE_OFFSETS).first
        val isQuestion = questionDistance < thresholds.questionMaxDistance
        if (isQuestion && !fullAnalysis) {
            return SlotAnalysis(slotIndex, SlotResult.Question, questionDistance, emptyList())
        }

        val ranking = rankCandidates(region, slotIndex)
        if (isQuestion) {
            return SlotAnalysis(slotIndex, SlotResult.Question, questionDistance, ranking)
        }

        val best = ranking.first()
        if (best.distance > thresholds.factorMaxDistance) {
            return SlotAnalysis(slotIndex, SlotResult.Unknown, questionDistance, ranking)
        }
        val second = ranking.getOrNull(1)
        if (second == null || second.distance - best.distance >= thresholds.minGap) {
            return SlotAnalysis(slotIndex, SlotResult.Factor(best.factorId), questionDistance, ranking)
        }

        // 差距不足：在距离最优 + minGap 以内的候选之间做二次比较
        val pool = ranking.filter { it.distance - best.distance < thresholds.minGap }
        val (winner, margin) = resolveBySecondary(region, pool)
        val result = if (winner != null) {
            SlotResult.Factor(winner.factorId)
        } else {
            SlotResult.Ambiguous(pool.map { it.factorId }.toSet())
        }
        return SlotAnalysis(
            slotIndex, result, questionDistance, ranking,
            secondaryResolved = winner != null,
            secondaryMargin = margin
        )
    }

    /** 粗搜全部候选，再精修前 [REFINE_TOP] 个，返回按距离升序的列表。 */
    private fun rankCandidates(region: ArgbImage, slotIndex: Int): List<CandidateScore> {
        val candidates = bank.candidatesForSlot(slotIndex)
        val coarse = candidates.map { c ->
            val (distance, dx, dy) = bestOffset(c.template, region, COARSE_OFFSETS)
            CandidateScore(c.factor.id, distance, dx, dy)
        }.sortedBy { it.distance }

        val refined = coarse.take(REFINE_TOP).map { score ->
            val (distance, dx, dy) = bestOffset(bank.templates.getValue(score.factorId).template, region, FINE_OFFSETS)
            CandidateScore(score.factorId, distance, dx, dy)
        }
        return (refined + coarse.drop(REFINE_TOP)).sortedBy { it.distance }
    }

    /**
     * 在 [pool] 内两两比较，只有赢下所有对手的候选才返回。
     *
     * 每一对只看「两者都不透明且颜色差 > secondaryPixelDiff」的像素，各自按主搜索得到的最优偏移取截图像素。
     * NOTE: 「只有一方不透明」的像素不参与比较，因为另一方在该位置的预期颜色是未知的槽位背景，
     * 算不出可比的距离。实测只用共同不透明像素，「天赋选择+1 / 天赋自助餐」的优势就有 56.91 以上（架构方案第 3.2 节）。
     */
    private fun resolveBySecondary(region: ArgbImage, pool: List<CandidateScore>): Pair<CandidateScore?, Double?> {
        for (a in pool) {
            var minMargin = Double.MAX_VALUE
            val winsAll = pool.all { b ->
                if (b === a) return@all true
                val margin = secondaryAdvantage(region, a, b) ?: return@all false
                minMargin = minOf(minMargin, margin)
                margin >= thresholds.secondaryMinGap
            }
            if (winsAll) return a to minMargin
        }
        return null to null
    }

    /**
     * 返回 a 相对 b 的二次比较优势（b 的距离 − a 的距离），为负表示 a 更差。
     *
     * 差异像素少于 `secondaryMinPixels` 时无法比较，返回 null。
     */
    private fun secondaryAdvantage(region: ArgbImage, a: CandidateScore, b: CandidateScore): Double? {
        val ta = bank.templates.getValue(a.factorId).template
        val tb = bank.templates.getValue(b.factorId).template
        var count = 0
        var sumA = 0L
        var sumB = 0L
        for (i in 0 until ta.maskSize) {
            val x = ta.maskX[i]
            val y = ta.maskY[i]
            if (!tb.isOpaque(x, y)) continue
            val colorA = ta.maskColor[i]
            val colorB = tb.image[x, y]
            if (rgbAbsDiffSum(colorA, colorB) <= thresholds.secondaryPixelDiff * 3) continue
            count++
            sumA += rgbAbsDiffSum(region[MARGIN + a.offsetX + x, MARGIN + a.offsetY + y], colorA)
            sumB += rgbAbsDiffSum(region[MARGIN + b.offsetX + x, MARGIN + b.offsetY + y], colorB)
        }
        if (count < thresholds.secondaryMinPixels) return null
        val distA = sumA.toDouble() / (count * 3)
        val distB = sumB.toDouble() / (count * 3)
        return distB - distA
    }

    /** 在给定偏移集合里找距离最小的位置，返回 (距离, dx, dy)。 */
    private fun bestOffset(template: MaskedTemplate, region: ArgbImage, offsets: IntArray): Triple<Double, Int, Int> {
        var best = Double.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        for (dy in offsets) {
            for (dx in offsets) {
                val d = distance(template, region, MARGIN + dx, MARGIN + dy)
                if (d < best) {
                    best = d
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return Triple(best, bestDx, bestDy)
    }

    companion object {
        private const val MARGIN = ReferenceLayout.SEARCH_MARGIN
        private const val REFINE_TOP = 3
        private val COARSE_OFFSETS = intArrayOf(-2, 0, 2)
        private val FINE_OFFSETS = IntArray(7) { it - 3 }

        /**
         * 模板左上角放在 [region] 的 `(left, top)` 时，掩膜内像素的 RGB 平均绝对差（0~255）。
         *
         * NOTE: 不做越界检查，调用方须保证模板完全落在 [region] 内。
         */
        fun distance(template: MaskedTemplate, region: ArgbImage, left: Int, top: Int): Double {
            var sum = 0L
            val w = region.width
            for (i in 0 until template.maskSize) {
                val p = region.pixels[(top + template.maskY[i]) * w + left + template.maskX[i]]
                sum += rgbAbsDiffSum(p, template.maskColor[i])
            }
            return sum.toDouble() / (template.maskSize * 3)
        }
    }
}
