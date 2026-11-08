package io.github.futurelesswindchan.kazefactor.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.loop.Frame
import io.github.futurelesswindchan.kazefactor.engine.loop.ScreenCapturer
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * 基于无障碍服务 `takeScreenshot`（API 30+）的屏幕截图器（架构方案第 1.1、2.2 节）。
 *
 * `takeScreenshot` 返回的 `HardwareBuffer` 位于显存，不能直接读像素，
 * 必须先通过 `Bitmap.wrapHardwareBuffer` + `copy(ARGB_8888)` 转为内存位图，并立即关闭 `HardwareBuffer`。
 *
 * 具有底层自平滑（Self-smoothing）特性：
 * 内部记录 [lastCaptureTimeMs]，若两次截图发起间隔小于 [minIntervalMs]（默认 350ms），
 * 会在协程中自动挂起等待剩余冷却时间，杜绝因调用间隔不足导致 AOSP 返回
 * `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT(3)` 而引起上层误判（军师拍板 2026-11-08）。
 *
 * @param service 宿主无障碍服务
 * @param minIntervalMs 两次截图的最小间隔（毫秒），初值取 AOSP 常量 333 ms，第 5 步真机实测后校准为 350 ms
 */
class A11yScreenCapturer(
    private val service: AccessibilityService,
    override val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
) : ScreenCapturer {

    /** 单次截图尝试的详细结果，供真机调试记录失败码。 */
    sealed class AttemptResult {
        /** 截图成功。 */
        data class Success(val frame: BitmapFrame, val elapsedMs: Long) : AttemptResult()

        /**
         * 截图失败。
         *
         * @property errorCode `AccessibilityService.ERROR_TAKE_SCREENSHOT_*` 常量，或 [ERROR_UNSUPPORTED_SDK]
         * @property elapsedMs 本次调用耗时（毫秒）
         */
        data class Failure(val errorCode: Int, val elapsedMs: Long) : AttemptResult()
    }

    /** 互斥锁，确保多协程同时请求时不发生并发时序竞争与重叠调用 */
    private val captureMutex = Mutex()

    /** 上一次成功或发起截图的真实时间戳（SystemClock.elapsedRealtime） */
    @Volatile
    private var lastCaptureTimeMs: Long = 0L

    /** 最近一次截图失败的错误码（成功时置为 null），便于调试观察。 */
    @Volatile
    var lastErrorCode: Int? = null
        private set

    override suspend fun capture(): Frame? = when (val r = captureWithDetail()) {
        is AttemptResult.Success -> {
            lastErrorCode = null
            r.frame
        }
        is AttemptResult.Failure -> {
            lastErrorCode = r.errorCode
            null
        }
    }

    /**
     * 执行一次截图并返回详细结果（含错误码与耗时），自带冷却平滑与互斥保护。
     */
    suspend fun captureWithDetail(): AttemptResult = captureMutex.withLock {
        // 自平滑冷却：若距上次截图时间小于 minIntervalMs，自动挂起等待，绝不强行撞墙
        val now = SystemClock.elapsedRealtime()
        val timeSinceLast = now - lastCaptureTimeMs
        if (timeSinceLast < minIntervalMs) {
            val waitMs = minIntervalMs - timeSinceLast
            delay(waitMs)
        }

        val startMs = SystemClock.elapsedRealtime()
        lastCaptureTimeMs = startMs

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AttemptResult.Failure(ERROR_UNSUPPORTED_SDK, 0L)
        }
        takeScreenshotApi30(startMs)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotApi30(startMs: Long): AttemptResult =
        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                DIRECT_EXECUTOR,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val softwareBitmap = try {
                            screenshot.hardwareBuffer.use { buffer ->
                                val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                try {
                                    hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    hwBitmap?.recycle()
                                }
                            }
                        } catch (_: Throwable) {
                            null
                        }
                        val elapsed = SystemClock.elapsedRealtime() - startMs
                        if (!cont.isActive) {
                            softwareBitmap?.recycle()
                            return
                        }
                        if (softwareBitmap != null) {
                            cont.resume(AttemptResult.Success(BitmapFrame(softwareBitmap), elapsed))
                        } else {
                            cont.resume(
                                AttemptResult.Failure(
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
                                    elapsed
                                )
                            )
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val elapsed = SystemClock.elapsedRealtime() - startMs
                        if (cont.isActive) {
                            cont.resume(AttemptResult.Failure(errorCode, elapsed))
                        }
                    }
                }
            )
        }

    /**
     * 持有 `ARGB_8888` 软件 [Bitmap] 的 [Frame] 实现。
     *
     * [crop] 直接调用 [Bitmap.getPixels] 读取目标矩形区域，无需先把整屏转为 `IntArray`。
     */
    class BitmapFrame(val bitmap: Bitmap) : Frame {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height

        override fun crop(x: Int, y: Int, w: Int, h: Int): ArgbImage {
            require(w > 0 && h > 0) { "裁剪尺寸必须为正：${w}x$h" }
            require(x >= 0 && y >= 0 && x + w <= width && y + h <= height) {
                "裁剪区域 ($x, $y, $w, $h) 超出画面 (${width}x$height)"
            }
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, x, y, w, h)
            return ArgbImage(w, h, pixels)
        }

        override fun close() {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    companion object {
        /**
         * 两次截图请求的最小间隔（毫秒）。
         *
         * 2026-11-07 真机（vivo V2312A，Android 13）实测：AOSP 限流阈值为 333 ms（实测最短通过间隔 332 ms），
         * 间隔不足时返回 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT(3)`。取 350 ms 留出安全余量。
         */
        const val DEFAULT_MIN_INTERVAL_MS = 350L

        /** 系统版本低于 Android 11（API 30）时的自定义错误码。 */
        const val ERROR_UNSUPPORTED_SDK = -100

        /** 在回调所在线程直接执行，避免主线程排队额外增加延迟。 */
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }

        /** 把错误码转成可读说明，方便真机调试日志展示。 */
        fun errorCodeToString(code: Int): String = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR(1)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS(2)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT(3)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY(4)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "INVALID_WINDOW(5)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW(6)"
            ERROR_UNSUPPORTED_SDK -> "UNSUPPORTED_SDK(<API30)"
            else -> "UNKNOWN($code)"
        }
    }
}
