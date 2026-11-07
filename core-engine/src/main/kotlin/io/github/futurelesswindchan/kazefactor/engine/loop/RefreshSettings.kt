package io.github.futurelesswindchan.kazefactor.engine.loop

/**
 * 自动刷新的运行参数（架构方案第 1.5 节）。
 *
 * @property frequencyPerSecond 用户设置的刷新频率（1~10 次/秒），默认 3
 * @property maxConsecutiveFailures 连续识别失败多少帧就暂停，默认 3（架构方案的 N）
 * @property maxRefreshCount 总次数上限；为 null 时不限制。
 *   默认 = 期望次数 × 3（至少 100），由调用方按配方概率计算后传入
 * @property maxSameResultCount 点击后连续多少次识别出完全相同的结果就判为「点击无效」，默认 3
 * @throws IllegalArgumentException 频率不在 1~10 之间，或 N ≤ 0 时
 */
data class RefreshSettings(
    val frequencyPerSecond: Int = DEFAULT_FREQUENCY,
    val maxConsecutiveFailures: Int = DEFAULT_N,
    val maxRefreshCount: Int? = null,
    val maxSameResultCount: Int = DEFAULT_SAME_RESULT_COUNT
) {
    init {
        require(frequencyPerSecond in 1..10) { "频率必须在 1~10 之间：$frequencyPerSecond" }
        require(maxConsecutiveFailures > 0) { "连续失败上限必须为正：$maxConsecutiveFailures" }
        require(maxSameResultCount > 0) { "相同结果上限必须为正：$maxSameResultCount" }
        maxRefreshCount?.let { require(it > 0) { "总次数上限必须为正：$it" } }
    }

    /** 用户频率对应的两次截图最小间隔（毫秒）。 */
    val userIntervalMs: Long get() = 1000L / frequencyPerSecond

    companion object {
        /** 默认频率：3 次/秒，与无障碍截图的上限一致。 */
        const val DEFAULT_FREQUENCY = 3

        /** 默认连续识别失败上限（N）。 */
        const val DEFAULT_N = 3

        /** 默认「点击后相同结果」上限。 */
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
