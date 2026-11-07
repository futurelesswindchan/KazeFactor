package io.github.futurelesswindchan.kazefactor.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KazeFactor 无障碍服务（架构方案第 2.2、4、5、7 节）。
 *
 * 职责：
 * 1. 持有 [A11yScreenCapturer]（无障碍截图）与 [GestureTapper]（手势点击）。
 * 2. 监听 `TYPE_WINDOW_STATE_CHANGED` 事件，记录当前前台应用包名（第 4 节第 5 条）。
 * 3. 第 5 步临时职责：提供「截图一次并保存」「点击刷新一次」「连续截图 20 次」三项真机调试动作，
 *    以及一个左上角 `TYPE_ACCESSIBILITY_OVERLAY` 迷你调试条（第 6 步替换为正式悬浮窗）。
 */
class KazeAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var screenCapturer: A11yScreenCapturer
        private set

    lateinit var gestureTapper: GestureTapper
        private set

    private var windowManager: WindowManager? = null
    private var debugOverlayView: LinearLayout? = null
    private var overlayStatusText: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenCapturer = A11yScreenCapturer(this)
        gestureTapper = GestureTapper(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        _isConnected.value = true
        appendLog("无障碍服务已连接 (SDK=${android.os.Build.VERSION.SDK_INT})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // 忽略本应用与常见系统输入法窗口切换（架构方案第 4 节第 5 条）
            if (pkg != packageName && !pkg.contains("inputmethod", ignoreCase = true)) {
                _foregroundPackage.value = pkg
            }
        }
    }

    override fun onInterrupt() {
        appendLog("无障碍服务被中断 (onInterrupt)")
    }

    override fun onDestroy() {
        hideDebugOverlay()
        if (instance === this) {
            instance = null
            _isConnected.value = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ==================== 第 5 步：临时真机调试悬浮条 ====================

    /** 显示或隐藏左上角 `TYPE_ACCESSIBILITY_OVERLAY` 调试条。 */
    fun setDebugOverlayVisible(visible: Boolean) {
        if (visible) showDebugOverlay() else hideDebugOverlay()
    }

    @SuppressLint("SetTextI18n")
    private fun showDebugOverlay() {
        if (debugOverlayView != null) return
        val wm = windowManager ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = dp(10)
            val padV = dp(6)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 25, 32, 48))
                cornerRadius = dp(12).toFloat()
            }
        }

        val statusTv = TextView(this).apply {
            text = "Kaze 调试"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, dp(8), 0)
        }
        overlayStatusText = statusTv
        container.addView(statusTv)

        fun addBtn(label: String, onClick: () -> Unit) {
            val btn = Button(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
            container.addView(btn, lp)
        }

        addBtn("截1张") { debugCaptureOnce(delayMs = 0L) }
        addBtn("点刷新") { debugTapRefreshOnce(delayMs = 0L) }
        addBtn("连截20次") { debugCapture20Times(delayMs = 0L) }
        addBtn("×") { hideDebugOverlay() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 放在屏幕左上角，避开右侧的挑战因子面板和 (1377, 433) 刷新按钮
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(16)
        }

        try {
            wm.addView(container, params)
            debugOverlayView = container
            _isOverlayShowing.value = true
            appendLog("已显示 TYPE_ACCESSIBILITY_OVERLAY 调试悬浮条")
        } catch (e: Throwable) {
            appendLog("显示悬浮条失败：${e.message}")
        }
    }

    private fun hideDebugOverlay() {
        val view = debugOverlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Throwable) {
        }
        debugOverlayView = null
        overlayStatusText = null
        _isOverlayShowing.value = false
    }

    private fun updateOverlayStatus(text: String) {
        overlayStatusText?.text = text
    }

    // ==================== 第 5 步：三项真机验证动作 ====================

    /**
     * 调试动作 ①：截图一次并保存到应用目录。
     *
     * 保存路径：`<externalFilesDir 或 filesDir>/debug_captures/capture_<时间戳>.png`。
     * 主人可检查保存的 PNG 是否与手机截屏一致，以及左上角的 `TYPE_ACCESSIBILITY_OVERLAY` 调试条是否被截入。
     */
    fun debugCaptureOnce(delayMs: Long = 0L) {
        serviceScope.launch {
            if (delayMs > 0) {
                updateOverlayStatus("倒计时 ${delayMs / 1000}s...")
                delay(delayMs)
            }
            updateOverlayStatus("正在截图...")
            when (val res = screenCapturer.captureWithDetail()) {
                is A11yScreenCapturer.AttemptResult.Success -> {
                    val frame = res.frame
                    try {
                        val file = withContext(Dispatchers.IO) {
                            saveBitmapToDebugDir(frame.bitmap)
                        }
                        val msg = buildString {
                            appendLine("✅ 截图一次成功！")
                            appendLine("尺寸：${frame.width} × ${frame.height}，耗时：${res.elapsedMs} ms")
                            appendLine("已保存：${file.absolutePath}")
                            append("请查看该图片是否包含左上角「Kaze 调试」悬浮条。")
                        }
                        updateOverlayStatus("截图成功 ${frame.width}x${frame.height}")
                        appendLog(msg)
                    } finally {
                        frame.close()
                    }
                }
                is A11yScreenCapturer.AttemptResult.Failure -> {
                    val codeStr = A11yScreenCapturer.errorCodeToString(res.errorCode)
                    updateOverlayStatus("截图失败 $codeStr")
                    appendLog("❌ 截图失败：$codeStr（耗时 ${res.elapsedMs} ms）")
                }
            }
        }
    }

    /**
     * 调试动作 ②：点击刷新按钮一次。
     *
     * 按当前屏幕尺寸把参考坐标 (1377, 433) 换算到屏幕坐标（2388×1080 下就是 (1377, 433)，
     * 其他分辨率按屏幕高度等比缩放并保持相对屏幕中心的水平偏移）后注入一次点击。
     */
    fun debugTapRefreshOnce(delayMs: Long = 0L) {
        serviceScope.launch {
            if (delayMs > 0) {
                updateOverlayStatus("倒计时 ${delayMs / 1000}s...")
                delay(delayMs)
            }
            val metrics = resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels
            // 横竖屏容错：取长边为宽、短边为高计算参考坐标
            val w = maxOf(screenW, screenH)
            val h = minOf(screenW, screenH)
            val scale = h.toDouble() / ReferenceLayout.HEIGHT
            val originX = (w - ReferenceLayout.WIDTH * scale) / 2.0
            val tapX = (originX + ReferenceLayout.REFRESH_TAP_X * scale).toFloat()
            val tapY = (ReferenceLayout.REFRESH_TAP_Y * scale).toFloat()

            updateOverlayStatus("点击 (${tapX.toInt()}, ${tapY.toInt()})...")
            val startMs = SystemClock.elapsedRealtime()
            val ok = gestureTapper.tap(tapX, tapY)
            val elapsed = SystemClock.elapsedRealtime() - startMs

            val msg = if (ok) {
                "✅ 点击刷新成功！坐标=(${tapX.toInt()}, ${tapY.toInt()})，屏幕=${w}×${h}，耗时=${elapsed} ms"
            } else {
                "❌ 点击手势被系统拒绝/取消：坐标=(${tapX.toInt()}, ${tapY.toInt()})，耗时=${elapsed} ms"
            }
            updateOverlayStatus(if (ok) "点击成功" else "点击被拒")
            appendLog(msg)
        }
    }

    /**
     * 调试动作 ③：连续截图 20 次，实测最小间隔与失败码。
     *
     * 分两组测试：
     * - **Part A（紧贴调用 5 次，间隔 0 ms）**：验证不等待时系统是否返回 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT(3)`。
     * - **Part B（自适应探测 20 次成功截图）**：每次截图完成后从 50 ms 起步尝试，若遇 `INTERVAL_TIME_SHORT(3)`
     *   则每 25 ms 重试一次直到成功，记录连续 20 次**成功截图**之间相隔的真实最小间隔、平均间隔、单次截图耗时和所有出现的失败码。
     */
    fun debugCapture20Times(delayMs: Long = 0L) {
        serviceScope.launch {
            if (delayMs > 0) {
                updateOverlayStatus("倒计时 ${delayMs / 1000}s...")
                delay(delayMs)
            }
            updateOverlayStatus("正在连截测速...")
            appendLog("⏳ 开始「连续截图 20 次」实测...")

            val report = StringBuilder()
            report.appendLine("=== KazeFactor 第 5 步：连续截图 20 次实测报告 ===")
            report.appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT})")

            // Part A：0 ms 紧贴连调 5 次，触发并记录失败码
            report.appendLine()
            report.appendLine("--- Part A：0 ms 紧贴调用 5 次（观察限流错误码）---")
            for (i in 1..5) {
                val t0 = SystemClock.elapsedRealtime()
                when (val r = screenCapturer.captureWithDetail()) {
                    is A11yScreenCapturer.AttemptResult.Success -> {
                        val w = r.frame.width
                        val h = r.frame.height
                        r.frame.close()
                        report.appendLine("  #$i [t=${t0}ms] 成功 (${w}x${h})，调用耗时=${r.elapsedMs} ms")
                    }
                    is A11yScreenCapturer.AttemptResult.Failure -> {
                        val codeStr = A11yScreenCapturer.errorCodeToString(r.errorCode)
                        report.appendLine("  #$i [t=${t0}ms] 失败 code=$codeStr，调用耗时=${r.elapsedMs} ms")
                    }
                }
            }

            // 等待 500 ms 让限流冷却，开始 Part B
            delay(500)

            // Part B：凑满 20 次成功截图，每次成功后每隔 25 ms 轮询试探系统允许的最早下一次成功时刻
            report.appendLine()
            report.appendLine("--- Part B：连续 20 次成功截图（每 25 ms 试探最短通过间隔）---")
            val successStartTimes = mutableListOf<Long>()
            val successCaptureCosts = mutableListOf<Long>()
            val errorCounts = linkedMapOf<Int, Int>()
            val baseTime = SystemClock.elapsedRealtime()

            var successIndex = 0
            var totalAttempts = 0
            while (successIndex < 20 && totalAttempts < 400) {
                totalAttempts++
                val attemptStart = SystemClock.elapsedRealtime()
                when (val r = screenCapturer.captureWithDetail()) {
                    is A11yScreenCapturer.AttemptResult.Success -> {
                        r.frame.close()
                        successIndex++
                        val sinceLast = if (successStartTimes.isEmpty()) 0L else attemptStart - successStartTimes.last()
                        successStartTimes += attemptStart
                        successCaptureCosts += r.elapsedMs
                        updateOverlayStatus("连截 $successIndex/20 (${sinceLast}ms)")
                        report.appendLine(
                            "  成功 #$successIndex：距上次成功发起=${sinceLast} ms，本次截图耗时=${r.elapsedMs} ms (t=+${attemptStart - baseTime} ms)"
                        )
                    }
                    is A11yScreenCapturer.AttemptResult.Failure -> {
                        errorCounts[r.errorCode] = (errorCounts[r.errorCode] ?: 0) + 1
                        delay(25)
                    }
                }
            }

            val intervals = successStartTimes.zipWithNext { a, b -> b - a }
            val minInterval = intervals.minOrNull() ?: 0L
            val maxInterval = intervals.maxOrNull() ?: 0L
            val avgInterval = if (intervals.isNotEmpty()) intervals.average() else 0.0
            val avgCaptureCost = if (successCaptureCosts.isNotEmpty()) successCaptureCosts.average() else 0.0

            report.appendLine()
            report.appendLine("--- 汇总 ---")
            report.appendLine("成功帧数：${successStartTimes.size} / 20（总尝试 $totalAttempts 次）")
            report.appendLine("相邻两次成功发起间隔：最小 = $minInterval ms，平均 = ${"%.1f".format(Locale.US, avgInterval)} ms，最大 = $maxInterval ms")
            report.appendLine("单次 takeScreenshot+拷像素耗时：最小 = ${successCaptureCosts.minOrNull() ?: 0} ms，平均 = ${"%.1f".format(Locale.US, avgCaptureCost)} ms，最大 = ${successCaptureCosts.maxOrNull() ?: 0} ms")
            if (errorCounts.isEmpty()) {
                report.appendLine("失败码统计：无失败（0 ms 间隔即可连续截图！）")
            } else {
                val errSummary = errorCounts.entries.joinToString("，") { (code, cnt) ->
                    "${A11yScreenCapturer.errorCodeToString(code)} × $cnt 次"
                }
                report.appendLine("失败码统计：$errSummary")
            }

            val reportFile = withContext(Dispatchers.IO) {
                val dir = getDebugCaptureDir()
                File(dir, "benchmark_report.txt").apply {
                    writeText(report.toString(), Charsets.UTF_8)
                }
            }
            report.appendLine("报告已保存：${reportFile.absolutePath}")

            updateOverlayStatus("连截完成 min=${minInterval}ms")
            appendLog(report.toString())
        }
    }

    private fun getDebugCaptureDir(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "debug_captures").apply { mkdirs() }
    }

    private fun saveBitmapToDebugDir(bitmap: Bitmap): File {
        val dir = getDebugCaptureDir()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "capture_$stamp.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        @Volatile
        var instance: KazeAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        /** 无障碍服务当前是否已连接。 */
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _isOverlayShowing = MutableStateFlow(false)
        /** 调试悬浮条当前是否显示。 */
        val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

        private val _foregroundPackage = MutableStateFlow<String?>(null)
        /** 最近一次监测到的非本应用前台包名。 */
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

        private val _debugLog = MutableStateFlow("尚未执行调试操作。请先在系统设置中开启「KazeFactor 自动刷新」无障碍服务。")
        /** 最新调试输出，供 App 界面展示与一键复制。 */
        val debugLog: StateFlow<String> = _debugLog.asStateFlow()

        private fun appendLog(message: String) {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            _debugLog.value = "[$stamp]\n$message"
        }
    }
}
