package io.github.futurelesswindchan.kazefactor.domain.rule

import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.model.SlotConstraint

/**
 * 概率计算器：估算配方的命中概率和期望刷新次数
 *
 * 模型假设（游戏真实抽取算法未知，这里是"估算"）：
 * - 所有"合法"的 4 因子结果等概率出现
 * - 合法 = 符合 17 种槽位类别组合之一 + 4 个因子互不相同 + 不含冲突对
 *
 * 注意：unwanted 只影响"是否命中"，不会从游戏的抽取池里移除因子，
 * 所以总数始终是全部合法结果，排除得越多，命中率越低。
 * 将来可用刷新历史统计校正这个模型。
 */
class ProbabilityCalculator(private val db: FactorDatabase) {

    /** 各类别的因子 ID 列表（缓存，避免重复过滤） */
    private val idsByCategory = db.factors.groupBy({ it.category }, { it.id })

    /** 冲突关系的快速查询表 */
    private val conflictSet: Set<Pair<Int, Int>> = db.conflicts
        .filter { it.size == 2 }
        .flatMap { listOf(it[0] to it[1], it[1] to it[0]) }
        .toSet()

    /** 全部合法结果的数量与配方无关，只算一次 */
    private val totalValidCount: Long by lazy { countAll { true } }

    /**
     * 计算配方的命中概率（0.0 ~ 1.0）
     */
    fun calculate(recipe: Recipe): Double {
        val total = totalValidCount
        if (total == 0L) return 0.0
        val matching = countAll { combo -> recipe.matches(combo[0], combo[1], combo[2], combo[3]) }
        return matching.toDouble() / total
    }

    /**
     * 期望刷新次数（几何分布期望 = 1 / p）
     */
    fun expectedRefreshCount(recipe: Recipe): Double {
        val prob = calculate(recipe)
        return if (prob > 0) 1.0 / prob else Double.POSITIVE_INFINITY
    }

    /**
     * 遍历所有槽位组合下的全部合法结果，统计满足 predicate 的数量
     *
     * 规模：17 种组合，每种最多 20×15×15×9 ≈ 4 万，总量在百万级以内，PC/手机都可接受。
     * UI 调用时应放在后台协程。
     */
    private fun countAll(predicate: (IntArray) -> Boolean): Long {
        var count = 0L
        val combo = IntArray(4)

        for (constraint in SlotConstraint.entries) {
            val pools = constraint.toCategories().map { idsByCategory[it].orEmpty() }
            if (pools.any { it.isEmpty() }) continue

            fun dfs(slot: Int) {
                if (slot == 4) {
                    if (predicate(combo)) count++
                    return
                }
                for (id in pools[slot]) {
                    // 去重 + 冲突剪枝：与已放入的因子比较
                    var ok = true
                    for (prev in 0 until slot) {
                        val p = combo[prev]
                        if (p == id || (p to id) in conflictSet) {
                            ok = false
                            break
                        }
                    }
                    if (!ok) continue
                    combo[slot] = id
                    dfs(slot + 1)
                }
            }
            dfs(0)
        }
        return count
    }
}
