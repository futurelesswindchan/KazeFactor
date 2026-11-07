package io.github.futurelesswindchan.kazefactor.service

import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.futurelesswindchan.kazefactor.KazeFactorApplication
import io.github.futurelesswindchan.kazefactor.OverlayController
import io.github.futurelesswindchan.kazefactor.engine.loop.Decision
import io.github.futurelesswindchan.kazefactor.engine.loop.EngineState
import io.github.futurelesswindchan.kazefactor.engine.loop.RefreshEngine
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibrator
import io.github.futurelesswindchan.kazefactor.engine.vision.FrameAnalyzer
import io.github.futurelesswindchan.kazefactor.engine.vision.ProfileVerifier
import io.github.futurelesswindchan.kazefactor.overlay.OverlayViewModel
import io.github.futurelesswindchan.kazefactor.overlay.OverlayWindow
import io.github.futurelesswindchan.kazefactor.util.AssetRecognitionLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * KazeFactor 无障碍服务（架构方案第 2.2、4、5、7 节）。
 *
 * 职责：
 * 1. 持有 [A11yScreenCapturer]（无障碍截图）与 [GestureTapper]（手势点击）；
 * 2. 组装 [RefreshEngine]、[OverlayViewModel] 与 [OverlayWindow]（`TYPE_ACCESSIBILITY_OVERLAY` 悬浮窗）；
 * 3. 监听 `TYPE_WINDOW_STATE_CHANGED` 事件，当前台应用从游戏切换到其他应用时立即暂停引擎（第 4 节第 5 条）；
 * 4. 响应 [OverlayController.isOverlayRequested] 显示或隐藏悬浮窗。
 */
class KazeAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var screenCapturer: A11yScreenCapturer
        private set

    lateinit var gestureTapper: GestureTapper
        private set

    private var refreshEngine: RefreshEngine? = null
    private var overlayViewModel: OverlayViewModel? = null
    private var overlayWindow: OverlayWindow? = null

    /** 开始自动刷新时记录的游戏前台包名（架构方案第 4 节第 5 条）。 */
    @Volatile
    private var activeGamePackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = applicationContext as KazeFactorApplication
        screenCapturer = A11yScreenCapturer(this)
        gestureTapper = GestureTapper(this)

        val recognizerFactory = app.recognizerFactory
        val helpIcon = AssetRecognitionLoader.loadHelpIcon(this)
        val calibrator = Calibrator(helpIcon, recognizerFactory)
        val verifier = ProfileVerifier(recognizerFactory)
        val analyzer = FrameAnalyzer(recognizerFactory)
        val decision = Decision(app.factorDatabase)

        val engine = RefreshEngine(
            capturer = screenCapturer,
            tapper = gestureTapper,
            analyzer = analyzer,
            decision = decision,
            calibrator = calibrator,
            verifier = verifier
        )
        refreshEngine = engine

        val vm = OverlayViewModel(
            context = this,
            factorDatabase = app.factorDatabase,
            recipeRepository = app.recipeRepository,
            engineSettingsDataStore = app.engineSettingsDataStore,
            calibrationProfileDataStore = app.calibrationProfileDataStore,
            recognizerFactory = recognizerFactory,
            calibrator = calibrator,
            capturer = screenCapturer,
            refreshEngine = engine
        )
        overlayViewModel = vm

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val window = OverlayWindow(
            context = this,
            windowManager = wm,
            viewModel = vm,
            scope = serviceScope
        )
        overlayWindow = window

        instance = this
        _isConnected.value = true

        // 监听引擎进入 Running 状态时记下当前前台游戏包名
        serviceScope.launch {
            engine.state.collect { state ->
                if (state is EngineState.Running && activeGamePackage == null) {
                    activeGamePackage = _foregroundPackage.value
                } else if (state !is EngineState.Running) {
                    activeGamePackage = null
                }
            }
        }

        // 响应 OverlayController 的悬浮窗开关请求
        serviceScope.launch {
            OverlayController.isOverlayRequested.collect { show ->
                if (show) {
                    window.show()
                } else {
                    window.hide()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // 忽略本应用与常见系统输入法窗口切换（架构方案第 4 节第 5 条）
            if (pkg != packageName && !pkg.contains("inputmethod", ignoreCase = true)) {
                _foregroundPackage.value = pkg
                val expectedGame = activeGamePackage
                if (expectedGame != null && pkg != expectedGame) {
                    // 玩家切出游戏（如回到桌面或切换到其他应用），立即暂停自动刷新
                    refreshEngine?.pause()
                }
            }
        }
    }

    override fun onInterrupt() {
        refreshEngine?.pause()
    }

    override fun onDestroy() {
        refreshEngine?.stop()
        overlayWindow?.hide()
        overlayWindow = null
        overlayViewModel = null
        refreshEngine = null
        if (instance === this) {
            instance = null
            _isConnected.value = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: KazeAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        /** 无障碍服务当前是否已连接。 */
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _foregroundPackage = MutableStateFlow<String?>(null)
        /** 最近一次监测到的非本应用前台包名。 */
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()
    }
}
