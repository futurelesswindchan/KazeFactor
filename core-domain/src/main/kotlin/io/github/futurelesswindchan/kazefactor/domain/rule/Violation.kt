package io.github.futurelesswindchan.kazefactor.domain.rule

/**
 * 配方校验违规结果
 *
 * severity 决定 UI 行为：
 * - ERROR：阻塞"开始刷新"，需要用户修改或一键修复
 * - WARNING：只用小悬浮字提示，不阻塞，选择权交给用户
 */
sealed class Violation(val severity: Severity) {

    enum class Severity { ERROR, WARNING }

    /** 是否阻塞开始 */
    val isBlocking: Boolean get() = severity == Severity.ERROR

    /** R1: 想要的因子超过 4 个 */
    data class TooManyWanted(
        val count: Int,
        val wantedIds: Set<Int>
    ) : Violation(Severity.ERROR) {
        val suggestion: String
            get() = "最多只能选择 4 个想要的因子，当前选了 $count 个。建议保留前 4 个，其余移到可选。"
    }

    /** R2: 想要与不想要重叠 */
    data class WantedUnwantedOverlap(
        val overlappingIds: Set<Int>
    ) : Violation(Severity.ERROR) {
        val suggestion: String
            get() = "有 ${overlappingIds.size} 个因子同时在想要和不想要中。请移除其中一个列表中的重复项。"
    }

    /** R3: 想要的因子之间冲突 */
    data class ConflictInWanted(
        val conflictPairs: List<Pair<Int, Int>>
    ) : Violation(Severity.ERROR) {
        val suggestion: String
            get() = "有 ${conflictPairs.size} 对因子冲突，它们不能同时出现。请为每对冲突选择保留其中一个。"
    }

    /** R4: 排除后凑不出任何合法结果 */
    data class InsufficientPool(
        val availableCount: Int
    ) : Violation(Severity.ERROR) {
        val suggestion: String
            get() = "排除不想要的因子后（剩余 $availableCount 个），已经凑不出任何合法的因子组合。请减少不想要的数量。"
    }

    /** R5: 想要的组合不可能出现 */
    data class ImpossibleCombination(
        val wantedIds: Set<Int>,
        val reason: String
    ) : Violation(Severity.ERROR) {
        val suggestion: String
            get() = "这个因子组合在游戏中不可能出现：$reason"
    }

    /** R6: 命中率极低（仅提示，不阻塞） */
    data class LowProbability(
        val probability: Double,
        val expectedRefreshCount: Double,
        val threshold: Double = 0.001
    ) : Violation(Severity.WARNING) {
        val suggestion: String
            get() = "命中概率约 ${"%.4f".format(probability * 100)}%，预计要刷约 ${expectedRefreshCount.toLong()} 次。"
    }
}
