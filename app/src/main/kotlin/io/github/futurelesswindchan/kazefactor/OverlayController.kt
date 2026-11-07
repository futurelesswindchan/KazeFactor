package io.github.futurelesswindchan.kazefactor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内悬浮窗控制单例（架构方案第 2.2 节）。
 *
 * App 界面（如配方列表、权限引导页）通过本单例请求显示或隐藏悬浮窗，
 * 无障碍服务 `KazeAccessibilityService` 监听 [isOverlayRequested] 并同步控制 `OverlayWindow`。
 */
object OverlayController {

    private val _isOverlayRequested = MutableStateFlow(false)
    /** 用户是否请求显示悬浮窗。 */
    val isOverlayRequested: StateFlow<Boolean> = _isOverlayRequested.asStateFlow()

    private val _isOverlayShowing = MutableStateFlow(false)
    /** 悬浮窗当前是否实际显示在屏幕上（由服务回写）。 */
    val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

    /** 请求显示悬浮窗。 */
    fun requestShow() {
        _isOverlayRequested.value = true
    }

    /** 请求关闭悬浮窗。 */
    fun requestHide() {
        _isOverlayRequested.value = false
    }

    /** 切换悬浮窗显示状态。 */
    fun toggle() {
        _isOverlayRequested.value = !_isOverlayRequested.value
    }

    /** 由无障碍服务同步当前悬浮窗实际挂载状态。 */
    fun updateActualShowing(showing: Boolean) {
        _isOverlayShowing.value = showing
        if (!showing) {
            _isOverlayRequested.value = false
        }
    }
}
