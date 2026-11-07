package io.github.futurelesswindchan.kazefactor.engine.loop

import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.model.SlotConstraint
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult

/**
 * 一帧识别结果对配方的判定（架构方案第 3.2 节「易混图标」第 2 条、第 4 节）。
 */
sealed class DecisionResult {
    /**
     * 命中：所有可能的组合都满足配方。
     *
     * @property factorIds 用于展示的 4 个因子 id。槽位有 [SlotResult.Ambiguous] 时取第一个合法组合
     */
    data class Hit(val factorIds: List<Int>) : DecisionResult()

    /**
     * 未命中：所有可能的组合都不满足配方，可以点刷新。
     *
     * @property factorIds 用于「点击后画面是否变化」比较的结果；有 Ambiguous 时取第一个合法组合
     */
    data class Miss(val factorIds: List<Int>) : DecisionResult()

    /**
     * 看不懂：不点击，重新截图，计入识别失败。
     *
     * @property reason 原因，只用于日志和测试断言
     */
    data class Uncertain(val reason: UncertainReason) : DecisionResult()
}

/** [DecisionResult.Uncertain] 的原因。 */
enum class UncertainReason {
    /** 有槽位是 [SlotResult.Unknown]。 */
    UNKNOWN_SLOT,

    /** 开关为开却有槽位是问号，多半是过渡帧（架构方案第 3.3 节）。 */
    QUESTION_SLOT,

    /** 展开后没有任何组合符合 17 种类别组合、互不重复与冲突规则。 */
    NO_VALID_COMBINATION,

    /** 展开后有的组合命中、有的不命中。 */
    CONFLICTING_OUTCOMES
}

/**
 * 把 4 个槽位结果和配方判定为 HIT / MISS / UNCERTAIN。纯函数，不持有可变状态。
 *
 * 规则：
 * 1. 有 `Unknown` 或 `Question` → UNCERTAIN。
 * 2. 把每个 `Ambiguous` 槽位展开成所有候选，求 4 个槽位的笛卡尔积。
 * 3. 去掉不合法的组合：类别不属于 17 种组合之一、有重复因子、含冲突对。
 *    合法组合一个都不剩 → UNCERTAIN（识别结果自相矛盾，不能信）。
 * 4. 剩下的组合全部命中 → HIT；全部不命中 → MISS；结论不一致 → UNCERTAIN。
 *
 * 配方没有勾选「天赋选择+1 / 天赋自助餐」时，两者分不清对结论没有影响，照常刷新。
 *
 * ```
 * val decision = Decision(database)
 * when (val r = decision.decide(analysis.slotResults, recipe)) {
 *     is DecisionResult.Hit -> stopAndCelebrate(r.factorIds)
 *     is DecisionResult.Miss -> tapRefresh()
 *     is DecisionResult.Uncertain -> retryCapture()
 * }
 * ```
 *
 * @param database 因子数据库，用于查类别和冲突
 */
class Decision(private val database: FactorDatabase) {

    private val categoryById = database.factors.associate { it.id to it.category }

    /**
     * 判定一帧。
     *
     * @param slots 4 个槽位结果，下标 0~3 对应第 1~4 槽
     * @param recipe 目标配方
     * @throws IllegalArgumentException [slots] 不是 4 个时
     */
    fun decide(slots: List<SlotResult>, recipe: Recipe): DecisionResult {
        require(slots.size == 4) { "必须正好有 4 个槽位结果，实际 ${slots.size} 个" }

        if (slots.any { it == SlotResult.Unknown }) return DecisionResult.Uncertain(UncertainReason.UNKNOWN_SLOT)
        if (slots.any { it == SlotResult.Question }) return DecisionResult.Uncertain(UncertainReason.QUESTION_SLOT)

        val candidates: List<List<Int>> = slots.map { slot ->
            when (slot) {
                is SlotResult.Factor -> listOf(slot.factorId)
                // 排序只为让「第一个合法组合」稳定，便于测试和日志对比
                is SlotResult.Ambiguous -> slot.candidateIds.sorted()
                else -> error("前面已排除 Unknown 与 Question")
            }
        }

        val valid = cartesian(candidates).filter(::isValidCombination)
        if (valid.isEmpty()) return DecisionResult.Uncertain(UncertainReason.NO_VALID_COMBINATION)

        val outcomes = valid.map { recipe.matches(it[0], it[1], it[2], it[3]) }
        return when {
            outcomes.all { it } -> DecisionResult.Hit(valid.first())
            outcomes.none { it } -> DecisionResult.Miss(valid.first())
            else -> DecisionResult.Uncertain(UncertainReason.CONFLICTING_OUTCOMES)
        }
    }

    /**
     * 4 个因子是否可能是一次合法的刷新结果：类别属于 17 种组合之一、互不重复、不含冲突对。
     *
     * 与 `ProbabilityCalculator` 枚举合法结果时用的是同一套规则。
     */
    fun isValidCombination(ids: List<Int>): Boolean {
        if (ids.size != 4 || ids.toSet().size != 4) return false
        val c = ids.map { categoryById[it] ?: return false }
        if (SlotConstraint.findPattern(c[0], c[1], c[2], c[3]) == null) return false
        for (i in 0 until 4) for (j in i + 1 until 4) {
            if (database.areConflicting(ids[i], ids[j])) return false
        }
        return true
    }

    private fun cartesian(lists: List<List<Int>>): List<List<Int>> =
        lists.fold(listOf(emptyList())) { acc, options -> acc.flatMap { prefix -> options.map { prefix + it } } }
}
