package io.github.futurelesswindchan.kazefactor.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import io.github.futurelesswindchan.kazefactor.engine.loop.Tapper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 基于无障碍服务 `dispatchGesture` 的屏幕点击器（架构方案第 2.2 节）。
 *
 * 在指定屏幕坐标注入一次短按触摸，挂起直到系统回调完成或取消。
 * 注意：`dispatchGesture` 的触摸会落到该坐标最上层的窗口，所以悬浮窗不能盖住刷新按钮。
 *
 * @param service 宿主无障碍服务
 * @param tapDurationMs 单次点击按住时长（毫秒），默认 40 ms
 */
class GestureTapper(
    private val service: AccessibilityService,
    private val tapDurationMs: Long = DEFAULT_TAP_DURATION_MS
) : Tapper {

    override suspend fun tap(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f) return false

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, tapDurationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    companion object {
        /** 默认点击按住时长（毫秒）。 */
        const val DEFAULT_TAP_DURATION_MS = 40L
    }
}
