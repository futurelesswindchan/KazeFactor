package io.github.futurelesswindchan.kazefactor.engine.loop

/**
 * 点击接口（架构方案第 1.3 节）。
 *
 * app 中由 `GestureTapper`（`dispatchGesture`）实现，测试中由假点击器记录点击坐标。
 */
interface Tapper {
    /**
     * 在屏幕坐标 ([x], [y]) 点击一次。
     *
     * @return 系统接受了点击请求时为 true；`dispatchGesture` 返回 false 或被取消时为 false
     */
    suspend fun tap(x: Float, y: Float): Boolean
}
