package io.github.futurelesswindchan.kazefactor.engine.loop

/**
 * 截图接口（架构方案第 1.3 节）。
 *
 * app 中由 `A11yScreenCapturer`（无障碍截图）实现，测试中由假截图器按顺序返回预录截图。
 */
interface ScreenCapturer {
    /**
     * 截取当前屏幕画面。
     *
     * @return 一帧截图；截图失败时返回 null（例如系统拒绝截图请求）
     */
    suspend fun capture(): Frame?

    /**
     * 两次截图之间的最小间隔（毫秒）。
     *
     * 无障碍截图的 AOSP 限制为 333 ms（约 3 次/秒），第 5 步真机实测后修正。
     * 状态机按 `max(1000 / 用户频率, minIntervalMs)` 控制节拍。
     */
    val minIntervalMs: Long
}
