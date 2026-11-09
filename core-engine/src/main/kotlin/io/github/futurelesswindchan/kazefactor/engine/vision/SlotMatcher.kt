package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.rgbAbsDiffSum

/**
 * 槽位识别判定阈值，距离单位均为「掩膜内 RGB 三通道平均绝对差」（0~255）。
 *
 * @property questionMaxDistance 与问号模板的距离低于此值时直接判定为问号
 * @property factorMaxDistance 最优候选因子距离不超过此值时才视为有效因子匹配
 * @property minGap 次优与最优距离之差达到此值时直接唯一确定，否则进入差异像素二次比较
 * @property secondaryPixelDiff 二次比较时，两张模板同一位置像素的平均通道差超过此值才计为差异像素
 * @property secondaryMinGap 二次比较中胜者对竞争对手的最小距离优势阈值
 * @property secondaryMinPixels 有效差异像素数量少于此值时不执行二次比较，直接判定为模糊候选
 */
data class MatchThresholds(
    val questionMaxDistance: Double = 10.0,
    val factorMaxDistance: Double = 38.0,
    val minGap: Double = 8.0,
    val secondaryPixelDiff: Int = 40,
    val secondaryMinGap: Double = 8.0,
    val secondaryMinPixels: Int = 30
)

/**
 * 单个候选因子在最优平移偏移处的匹配得分。
 *
 * @property factorId 因子 ID
 * @property distance 最优偏移处的掩膜像素平均绝对差
 * @property offsetX 最优横向偏移（相对槽位标准位置，单位：px）
 * @property offsetY 最优纵向偏移（相对槽位标准位置，单位：px）
 */
data class CandidateScore(val factorId: Int, val distance: Double, val offsetX: Int, val offsetY: Int)

/**
 * 单个槽位的完整匹配分析结果，供识别决策、阈值报告及诊断包导出使用。
 *
 * @property slotIndex 槽位序号（1~4）
 * @property result 槽位最终判定结果
 * @property questionDistance 与该槽问号模板的最优距离
 * @property ranking 按距离升序排列的候选因子得分列表；当判定为问号且未开启完整分析时为空列表
 * @property secondaryResolved 是否经由差异像素二次比较才唯一确定结果
 * @property secondaryMargin 二次比较中胜者相对各竞争对手的最小距离优势；未触发或未分出胜负时为 null
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
 * 基于不透明掩膜的像素差模板匹配器。
 *
 * 匹配流程：
 * 1. 计算与当前槽位问号模板的距离，若低于 [MatchThresholds.questionMaxDistance] 则直接判定为 [SlotResult.Question]。
 * 2. 对该槽位所有合法候选因子在 [COARSE_OFFSETS]（`±4 px`，步长 2）内执行粗搜索，取距离最小的 [REFINE_TOP] 个候选在 [FINE_OFFSETS]（`-6..6 px`，步长 1）内精修对齐。
 * 3. 若最优距离超出 [MatchThresholds.factorMaxDistance]，判定为 [SlotResult.Unknown]；若次优与最优差距不足 [MatchThresholds.minGap]，则在差异像素上执行二次比较，仍无法区分时返回 [SlotResult.Ambiguous]。
 *
 * @param bank 因子模板库，提供各槽位经缩放后的候选模板
 * @param questionTemplates 第 1~4 槽的问号模板，尺寸须等于 [TemplateBank.slotWidth] × [TemplateBank.slotHeight]
 * @param thresholds 匹配判定阈值配置
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
            require(t.image.width == bank.slotWidth && t.image.height == bank.slotHeight) {
                "第 ${i + 1} 槽问号模板尺寸 ${t.image.width}x${t.image.height} 与槽位 ${bank.slotWidth}x${bank.slotHeight} 不符"
            }
        }
    }

    /** 搜索区域的宽度：槽位宽度加两侧 [ReferenceLayout.SEARCH_MARGIN]。 */
    val regionWidth: Int get() = bank.slotWidth + 2 * MARGIN

    /** 搜索区域的高度：槽位高度加两侧 [ReferenceLayout.SEARCH_MARGIN]。 */
    val regionHeight: Int get() = bank.slotHeight + 2 * MARGIN

    /**
     * 识别单个槽位图像区域。
     *
     * @param region 以槽位为中心、尺寸为 [regionWidth] × [regionHeight] 的裁剪图像
     * @param slotIndex 槽位序号（1~4）
     * @param fullAnalysis 为 true 时，即使已判定为问号也继续计算全部候选因子的距离排行榜
     * @throws IllegalArgumentException 当 [region] 尺寸不符或 [slotIndex] 越界时抛出
     */
    fun match(region: ArgbImage, slotIndex: Int, fullAnalysis: Boolean = false): SlotAnalysis {
        require(region.width == regionWidth && region.height == regionHeight) {
            "搜索区域应为 ${regionWidth}x$regionHeight，实际 ${region.width}x${region.height}"
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

        // 次优与最优差距不足时，在进入 minGap 范围内的候选池中执行二次比较
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

    /** 粗搜索全部候选因子，并对排名前 [REFINE_TOP] 的候选执行逐像素偏移精修，返回按距离升序排列的结果。 */
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
     * 在近似候选池 [pool] 内执行两两差异像素比较，仅当某候选战胜池内所有其他对手时将其作为胜者返回。
     *
     * 比较仅统计双方模板均为不透明且颜色通道差大于 [MatchThresholds.secondaryPixelDiff] 的像素位置。
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
     * 计算候选 [a] 相对于候选 [b] 在差异像素上的距离优势（`distB - distA`）。
     *
     * 当有效差异像素数少于 [MatchThresholds.secondaryMinPixels] 时返回 null。
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

    /** 在给定偏移集合 [offsets] 中搜索距离最小的位置，返回 `(最优距离, dx, dy)`。 */
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
        private val COARSE_OFFSETS = intArrayOf(-4, -2, 0, 2, 4)
        private val FINE_OFFSETS = IntArray(13) { it - 6 }

        /**
         * 计算模板左上角放置于 [region] 的 `(left, top)` 时，不透明掩膜内像素的 RGB 三通道平均绝对差（0~255）。
         *
         * NOTE: 调用方须保证模板在给定偏移下完整处于 [region] 边界内。
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
