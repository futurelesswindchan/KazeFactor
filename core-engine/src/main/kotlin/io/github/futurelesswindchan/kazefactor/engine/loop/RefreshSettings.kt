package io.github.futurelesswindchan.kazefactor.engine.loop

/**
 * 自动刷新的运行参数。
 *
 * @property frequencyPerSecond 刷新频率档位（1~10 次/秒），默认 [DEFAULT_FREQUENCY]
 * @property maxConsecutiveFailures 连续识别失败多少帧就暂停，默认 [DEFAULT_N]
 * @property maxRefreshCount 总次数上限；为 null 时不限制。
 *   默认按「期望次数 × 3（至少 100）」由调用方计算后传入
 * @property maxSameResultCount 点击后连续多少次识别出完全相同的结果就判为「点击无效」，默认 [DEFAULT_SAME_RESULT_COUNT]
 * @property intervalOverrideMs 显式指定的两次截图最小间隔（毫秒）；非空时优先生效（例如固定 2.5 次/秒对应 400 ms）
 * @throws IllegalArgumentException 频率不在 1~10 之间，或连续失败上限、次数上限、间隔非正时
 */
data class RefreshSettings(
    val frequencyPerSecond: Int = DEFAULT_FREQUENCY,
    val maxConsecutiveFailures: Int = DEFAULT_N,
    val maxRefreshCount: Int? = null,
    val maxSameResultCount: Int = DEFAULT_SAME_RESULT_COUNT,
    val intervalOverrideMs: Long? = null
) {
    init {
        require(frequencyPerSecond in 1..10) { "频率必须在 1~10 之间：$frequencyPerSecond" }
        require(maxConsecutiveFailures > 0) { "连续失败上限必须为正：$maxConsecutiveFailures" }
        require(maxSameResultCount > 0) { "相同结果上限必须为正：$maxSameResultCount" }
        maxRefreshCount?.let { require(it > 0) { "总次数上限必须为正：$it" } }
        intervalOverrideMs?.let { require(it > 0L) { "刷新间隔必须为正：$it" } }
    }

    /** 两次截图的最小间隔（毫秒）。 */
    val userIntervalMs: Long get() = intervalOverrideMs ?: (1000L / frequencyPerSecond)

    companion object {
        /** 固定稳定运行频率：2.5 次/秒（对应 400 ms 间隔）。 */
        const val FIXED_FREQUENCY_PER_SECOND = 2.5f

        /** 固定稳定运行间隔：400 毫秒（对应 2.5 次/秒）。 */
        const val FIXED_INTERVAL_MS = 400L

        /** 默认整数频率档位常量。 */
        const val DEFAULT_FREQUENCY = 3

        /** 默认连续识别失败上限。 */
        const val DEFAULT_N = 3

        /** 默认点击后相同结果上限。 */
        const val DEFAULT_SAME_RESULT_COUNT = 3

        /**
         * 计算默认的总次数上限：期望次数 × 3，至少 100。
         *
         * @param expectedRefreshCount `ProbabilityCalculator.expectedRefreshCount()` 的结果
         * @return 计算出的上限；概率为 0（期望无穷）时返回 null
         */
        fun defaultMaxRefreshCount(expectedRefreshCount: Double): Int? {
            if (expectedRefreshCount.isInfinite() || expectedRefreshCount.isNaN()) return null
            return maxOf(100, (expectedRefreshCount * 3).toInt())
        }
    }
}
