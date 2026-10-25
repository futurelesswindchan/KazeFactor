package io.github.futurelesswindchan.kazefactor.domain.rule

import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe

/**
 * 规则引擎：校验配方是否合法，并提供自动修复建议
 *
 * "能否出现"统一交给 ProbabilityCalculator 判断（概率为 0 即不可能），
 * 这样槽位类别、17 种组合、冲突对这些规则只在一个地方实现。
 */
class RuleEngine(
    private val db: FactorDatabase,
    private val probabilityCalculator: ProbabilityCalculator
) {

    fun validate(recipe: Recipe, lowProbabilityThreshold: Double = 0.001): List<Violation> {
        val violations = mutableListOf<Violation>()

        // R1: 想要的因子超过 4 个
        if (recipe.wantedFactorIds.size > 4) {
            violations += Violation.TooManyWanted(recipe.wantedFactorIds.size, recipe.wantedFactorIds)
        }

        // R2: 想要与不想要重叠
        val overlap = recipe.wantedFactorIds intersect recipe.unwantedFactorIds
        if (overlap.isNotEmpty()) {
            violations += Violation.WantedUnwantedOverlap(overlap)
        }

        // R3: 想要的因子之间冲突
        val conflictPairs = findConflictsInSet(recipe.wantedFactorIds)
        if (conflictPairs.isNotEmpty()) {
            violations += Violation.ConflictInWanted(conflictPairs)
        }

        // 前三条是"显而易见"的错误，有的话先让用户改，不再叠加后面的提示
        if (violations.isNotEmpty()) return violations

        // R4: 排除不想要的之后，已经凑不出任何合法的 4 因子结果
        val unwantedOnly = recipe.copy(wantedFactorIds = emptySet(), optionalFactorIds = emptySet(), minOptional = 0)
        if (probabilityCalculator.calculate(unwantedOnly) == 0.0) {
            val available = (db.getAllFactorIds() - recipe.unwantedFactorIds).size
            violations += Violation.InsufficientPool(availableCount = available)
            return violations
        }

        // R5: 想要的组合在游戏规则下不可能出现（槽位类别 / 17 种组合 / 冲突）
        val wantedOnly = unwantedOnly.copy(wantedFactorIds = recipe.wantedFactorIds, unwantedFactorIds = emptySet())
        if (recipe.wantedFactorIds.isNotEmpty() && probabilityCalculator.calculate(wantedOnly) == 0.0) {
            violations += Violation.ImpossibleCombination(
                wantedIds = recipe.wantedFactorIds,
                reason = "这些因子的类别无法同时放进任何一种合法的槽位组合"
            )
            return violations
        }

        // R6: 完整配方的命中率
        val probability = probabilityCalculator.calculate(recipe)
        if (probability == 0.0) {
            // 想要的本身可以出现，但和不想要/可选条件叠加后不可能
            violations += Violation.ImpossibleCombination(
                wantedIds = recipe.wantedFactorIds,
                reason = "加上不想要或可选条件后，没有任何结果能满足"
            )
        } else if (probability < lowProbabilityThreshold) {
            violations += Violation.LowProbability(
                probability = probability,
                expectedRefreshCount = 1.0 / probability,
                threshold = lowProbabilityThreshold
            )
        }

        return violations
    }

    /**
     * 是否允许开始刷新：只看阻塞级（ERROR）违规，WARNING（如 R6 低概率）不影响
     */
    fun canStart(recipe: Recipe, lowProbabilityThreshold: Double = 0.001): Boolean =
        validate(recipe, lowProbabilityThreshold).none { it.isBlocking }

    /** 完全没有任何提示（包括 WARNING） */
    fun isValid(recipe: Recipe, lowProbabilityThreshold: Double = 0.001): Boolean =
        validate(recipe, lowProbabilityThreshold).isEmpty()

    private fun findConflictsInSet(factorIds: Set<Int>): List<Pair<Int, Int>> {
        val ids = factorIds.toList()
        val result = mutableListOf<Pair<Int, Int>>()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                if (db.areConflicting(ids[i], ids[j])) result += ids[i] to ids[j]
            }
        }
        return result
    }

    /**
     * 针对单条违规生成修复后的配方；无法自动修复的原样返回
     */
    fun autoFix(recipe: Recipe, violation: Violation): Recipe = when (violation) {
        is Violation.TooManyWanted -> {
            val kept = recipe.wantedFactorIds.take(4).toSet()
            val moved = recipe.wantedFactorIds - kept
            recipe.copy(
                wantedFactorIds = kept,
                optionalFactorIds = recipe.optionalFactorIds + moved
            )
        }
        is Violation.WantedUnwantedOverlap ->
            recipe.copy(unwantedFactorIds = recipe.unwantedFactorIds - violation.overlappingIds)
        is Violation.ConflictInWanted ->
            recipe.copy(wantedFactorIds = recipe.wantedFactorIds - violation.conflictPairs.map { it.second }.toSet())
        is Violation.InsufficientPool ->
            recipe.copy(unwantedFactorIds = emptySet())
        is Violation.ImpossibleCombination,
        is Violation.LowProbability -> recipe
    }
}
