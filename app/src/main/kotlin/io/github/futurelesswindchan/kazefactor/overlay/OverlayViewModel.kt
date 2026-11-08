package io.github.futurelesswindchan.kazefactor.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.futurelesswindchan.kazefactor.data.datastore.CalibrationProfileDataStore
import io.github.futurelesswindchan.kazefactor.data.datastore.EngineSettingsDataStore
import io.github.futurelesswindchan.kazefactor.data.datastore.EngineSettingsPref
import io.github.futurelesswindchan.kazefactor.data.repository.RecipeRepository
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.rule.ProbabilityCalculator
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.loop.EngineState
import io.github.futurelesswindchan.kazefactor.engine.loop.PauseReason
import io.github.futurelesswindchan.kazefactor.engine.loop.RefreshEngine
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibration
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfile
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibrator
import io.github.futurelesswindchan.kazefactor.engine.vision.ProfileVerifier
import io.github.futurelesswindchan.kazefactor.engine.vision.RecognizerFactory
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import io.github.futurelesswindchan.kazefactor.engine.vision.Snapper
import io.github.futurelesswindchan.kazefactor.engine.vision.UserFrameInput
import io.github.futurelesswindchan.kazefactor.service.A11yScreenCapturer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 悬浮窗 ViewModel（架构方案第 2.2、3.4、4、5 节）。
 *
 * 职责：
 * 1. 串联 [RefreshEngine] 状态，驱动悬浮窗各形态（小球/面板/运行中/命中/手动引导/确认预览）；
 * 2. 统计刷新速度（最近 10 次的平均速度）与期望刷新次数（[ProbabilityCalculator]）；
 * 3. 自动校准与位置档案快速核验流转（架构方案第 3.4.2 节）；
 * 4. 驱动手动位置引导、[Snapper] 吸附与确认预览；
 * 5. 命中时短震动（可配置）；
 * 6. 提供「保存当前画面」功能，方便用户真机导出排查问题。
 */
class OverlayViewModel(
    private val context: Context,
    private val factorDatabase: FactorDatabase,
    private val recipeRepository: RecipeRepository,
    private val engineSettingsDataStore: EngineSettingsDataStore,
    private val calibrationProfileDataStore: CalibrationProfileDataStore,
    private val recognizerFactory: RecognizerFactory,
    private val calibrator: Calibrator,
    private val capturer: A11yScreenCapturer,
    private val refreshEngine: RefreshEngine,
    private val snapper: Snapper = Snapper()
) : ViewModel() {

    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    private var engineJob: Job? = null
    private val refreshTimestamps = ArrayDeque<Long>()
    private var lastObservedCount = 0
    private val probabilityCalculator = ProbabilityCalculator(factorDatabase)

    /** 供截屏避让时调用的透明度回调 */
    var onWindowAlphaRequest: (suspend (Float) -> Unit)? = null

    /** 截取一帧无悬浮窗自遮挡的纯净画面（隐藏视图并等待 100ms 确保 SurfaceFlinger 合成新帧） */
    private suspend fun captureCleanFrame(): io.github.futurelesswindchan.kazefactor.engine.loop.Frame? {
        onWindowAlphaRequest?.invoke(0f)
        try {
            kotlinx.coroutines.delay(100)
            return capturer.capture()
        } finally {
            onWindowAlphaRequest?.invoke(1f)
        }
    }

    init {
        // 1. 监听配方列表更新
        viewModelScope.launch {
            recipeRepository.getRecipes().collect { list ->
                val settings = engineSettingsDataStore.settingsFlow.first()
                val selected = list.firstOrNull { it.id == settings.lastSelectedRecipeId }
                    ?: list.firstOrNull()
                _uiState.update { current ->
                    val expected = selected?.let { probabilityCalculator.expectedRefreshCount(it) }
                        ?: Double.POSITIVE_INFINITY
                    current.copy(
                        recipes = list,
                        selectedRecipe = selected,
                        expectedRefreshCount = expected,
                        effectiveMaxCount = settings.toRefreshSettings(expected).maxRefreshCount
                    )
                }
            }
        }

        // 2. 监听引擎设置更新
        viewModelScope.launch {
            engineSettingsDataStore.settingsFlow.collect { pref ->
                val expected = _uiState.value.expectedRefreshCount
                val refreshSettings = pref.toRefreshSettings(expected)
                val actualCap = 1000f / maxOf(refreshSettings.userIntervalMs, capturer.minIntervalMs)
                _uiState.update { current ->
                    current.copy(
                        frequencyPerSecond = pref.frequencyPerSecond,
                        actualMaxCapPerSecond = actualCap,
                        maxCountLimitEnabled = pref.maxCountLimitEnabled,
                        effectiveMaxCount = refreshSettings.maxRefreshCount,
                        vibrateOnHit = pref.vibrateOnHit
                    )
                }
            }
        }

        // 3. 监听 RefreshEngine 状态机
        viewModelScope.launch {
            refreshEngine.state.collect { state ->
                handleEngineState(state)
            }
        }
    }

    /** 切换悬浮窗形态（小球 ⇄ 面板）。 */
    fun toggleBubblePanel() {
        _uiState.update { current ->
            when (current.mode) {
                OverlayMode.BUBBLE -> current.copy(mode = OverlayMode.PANEL)
                OverlayMode.PANEL -> current.copy(mode = OverlayMode.BUBBLE)
                else -> current
            }
        }
    }

    /** 收起为小球。 */
    fun collapseToBubble() {
        _uiState.update { current ->
            if (current.mode == OverlayMode.PANEL) current.copy(mode = OverlayMode.BUBBLE) else current
        }
    }

    /** 选择目标配方。 */
    fun selectRecipe(recipe: Recipe) {
        val expected = probabilityCalculator.expectedRefreshCount(recipe)
        viewModelScope.launch {
            engineSettingsDataStore.setLastSelectedRecipeId(recipe.id)
            val pref = engineSettingsDataStore.settingsFlow.first()
            val effective = pref.toRefreshSettings(expected).maxRefreshCount
            _uiState.update { current ->
                current.copy(
                    selectedRecipe = recipe,
                    expectedRefreshCount = expected,
                    effectiveMaxCount = effective
                )
            }
        }
    }

    /** 调整刷新频率（1~10）。 */
    fun updateFrequency(freq: Int) {
        viewModelScope.launch {
            engineSettingsDataStore.setFrequency(freq)
        }
    }

    /** 开关命中短震动。 */
    fun updateVibrateOnHit(enabled: Boolean) {
        viewModelScope.launch {
            engineSettingsDataStore.setVibrateOnHit(enabled)
        }
    }

    /**
     * 点击「开始」：按架构方案第 3.4.2 节的流程决策：
     * - 有当前截图尺寸的档案 → 快速核验通过直接开始；失败则尝试自动校准。
     * - 无档案 → 自动校准，成功后进入确认预览，失败则提示并引导手动位置设置。
     */
    fun startRefreshing() {
        val recipe = _uiState.value.selectedRecipe ?: return
        if (engineJob?.isActive == true) return

        _uiState.update { it.copy(isBusyCalibrating = true, statusMessage = "正在准备位置…") }
        engineJob = viewModelScope.launch(Dispatchers.Default) {
            val frame = captureCleanFrame()
            if (frame == null) {
                _uiState.update {
                    it.copy(
                        isBusyCalibrating = false,
                        statusMessage = "截图失败，请确认无障碍服务已开启"
                    )
                }
                return@launch
            }

            val screenW = frame.width
            val screenH = frame.height
            val fullImage = frame.crop(0, 0, screenW, screenH)
            val profile = calibrationProfileDataStore.getProfile(screenW, screenH)

            if (profile != null) {
                val verifier = ProfileVerifier(recognizerFactory)
                when (verifier.verify(fullImage, profile)) {
                    is ProfileVerifier.Result.Pass -> {
                        frame.close()
                        startEngineWithProfile(recipe, profile)
                        return@launch
                    }
                    is ProfileVerifier.Result.ToggleOff -> {
                        frame.close()
                        _uiState.update {
                            it.copy(
                                isBusyCalibrating = false,
                                mode = OverlayMode.RUNNING,
                                pauseReason = PauseReason.TOGGLE_OFF
                            )
                        }
                        return@launch
                    }
                    is ProfileVerifier.Result.Fail -> {
                        // 快速核验失败，不直接打扰用户，尝试先自动校准一次（架构方案第 3.4.2 节）
                    }
                }
            }

            // 走自动校准流程
            val calib = calibrator.calibrate(fullImage)
            if (calib != null) {
                // 自动校准成功，截取开关块并进入确认预览（架构方案第 3.4.6 节：首次或重新设置必须确认预览）
                val patch = ProfileVerifier.captureTogglePatch(fullImage, calib)
                val bitmap = (frame as? A11yScreenCapturer.BitmapFrame)?.bitmap?.let {
                    it.copy(it.config ?: Bitmap.Config.ARGB_8888, false)
                }
                frame.close()

                if (patch != null && bitmap != null) {
                    showConfirmPreview(
                        screenshot = bitmap,
                        calibration = calib,
                        source = CalibrationProfile.Source.AUTO,
                        tapX = ReferenceLayout.REFRESH_TAP_X.toDouble(),
                        tapY = ReferenceLayout.REFRESH_TAP_Y.toDouble(),
                        startAfterConfirm = true
                    )
                } else {
                    enterManualSetup(startAfterConfirm = true)
                }
            } else {
                frame.close()
                _uiState.update {
                    it.copy(
                        isBusyCalibrating = false,
                        statusMessage = "自动校准失败，请进入手动引导设置位置"
                    )
                }
                enterManualSetup(startAfterConfirm = true)
            }
        }
    }

    /** 暂停后恢复。 */
    fun resumeRefreshing() {
        if (_uiState.value.mode != OverlayMode.RUNNING) return
        _uiState.update { it.copy(pauseReason = null) }
        engineJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                refreshEngine.resume()
            } catch (e: Throwable) {
                // 上次没有成功取得位置或状态不匹配时，回退到重新开始
                startRefreshing()
            }
        }
    }

    /** 用户主动暂停。 */
    fun pauseRefreshing() {
        refreshEngine.pause()
    }

    /** 停止运行，返回面板。 */
    fun stopRefreshing() {
        engineJob?.cancel()
        engineJob = null
        refreshEngine.stop()
        refreshTimestamps.clear()
        _uiState.update {
            it.copy(
                mode = OverlayMode.PANEL,
                isBusyCalibrating = false,
                pauseReason = null,
                actualSpeedPerSec = 0f
            )
        }
    }

    /** 命中完成，返回面板。 */
    fun finishHit() {
        stopRefreshing()
    }

    /** 命中后继续刷。 */
    fun continueRefreshingAfterHit() {
        _uiState.update { it.copy(mode = OverlayMode.RUNNING, hitFactors = emptyList()) }
        startRefreshing()
    }

    /** 重新设置位置：进入全屏手动引导（架构方案第 3.4.2、3.4.3 节）。 */
    fun startManualCalibration() {
        viewModelScope.launch(Dispatchers.Default) {
            enterManualSetup(startAfterConfirm = false)
        }
    }

    /**
     * 进入手动引导：隐藏悬浮窗（由 OverlayWindow 协作），截一张干净画面，初始化 4 格轮廓。
     */
    suspend fun enterManualSetup(startAfterConfirm: Boolean) {
        _uiState.update { it.copy(isBusyCalibrating = true, statusMessage = "正在截取游戏画面…") }
        val frame = captureCleanFrame() ?: run {
            _uiState.update { it.copy(isBusyCalibrating = false, statusMessage = "截图失败") }
            return
        }
        val bitmap = (frame as? A11yScreenCapturer.BitmapFrame)?.bitmap?.let {
            it.copy(it.config ?: Bitmap.Config.ARGB_8888, false)
        }
        frame.close()
        if (bitmap == null) return

        val screenW = bitmap.width
        val screenH = bitmap.height
        // 初始轮廓：按屏幕高度等比换算的参考位置（架构方案第 3.4.3 节第 1 条）
        val scale = screenH.toDouble() / ReferenceLayout.HEIGHT
        val originX = (screenW - ReferenceLayout.WIDTH * scale) / 2.0
        val originY = 0.0
        val defaultCalib = Calibration(scale, scale, originX, originY)
        val initialInput = UserFrameInput.fromCalibration(defaultCalib)
        val defaultTapX = defaultCalib.toScreenX(ReferenceLayout.REFRESH_TAP_X.toDouble()).toFloat()
        val defaultTapY = defaultCalib.toScreenY(ReferenceLayout.REFRESH_TAP_Y.toDouble()).toFloat()

        _uiState.update {
            it.copy(
                mode = OverlayMode.MANUAL_SETUP,
                isBusyCalibrating = false,
                statusMessage = null,
                manualSetup = ManualSetupData(
                    screenshot = bitmap,
                    userFrame = initialInput,
                    tapScreenX = defaultTapX,
                    tapScreenY = defaultTapY,
                    startAfterConfirm = startAfterConfirm
                )
            )
        }
        // 进入手动引导后立即对初始框尝试一次吸附，若默认位置已在范围内则直接变绿
        performSnap()
    }

    /** 更新手动引导中用户拖动的轮廓坐标。 */
    fun updateManualUserFrame(input: UserFrameInput) {
        _uiState.update { current ->
            val setup = current.manualSetup ?: return@update current
            current.copy(manualSetup = setup.copy(userFrame = input, snapError = null))
        }
    }

    /** 更新手动引导中用户点击的刷新按钮位置。 */
    fun updateManualTapPoint(x: Float, y: Float) {
        _uiState.update { current ->
            val setup = current.manualSetup ?: return@update current
            current.copy(manualSetup = setup.copy(tapScreenX = x, tapScreenY = y))
        }
    }

    /** 用户拖完轮廓并松手后，执行 [Snapper] 吸附。 */
    fun performSnap() {
        val setup = _uiState.value.manualSetup ?: return
        if (setup.isSnapping) return

        _uiState.update { current ->
            current.copy(manualSetup = current.manualSetup?.copy(isSnapping = true, snapError = null))
        }

        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = setup.screenshot
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val fullImage = ArgbImage(w, h, pixels)

            val measured = snapper.measure(fullImage, setup.userFrame)
            if (measured.passesEdgeCheck) {
                _uiState.update { current ->
                    current.copy(
                        manualSetup = current.manualSetup?.copy(
                            isSnapping = false,
                            snappedCalibration = measured.calibration,
                            snapError = null
                        )
                    )
                }
            } else {
                // 架构兜底策略（军师拍板 2026-11-08）：
                // 若梯度吸附因高分屏插值模糊未达严苛覆盖率，依次检查梯度最优解与用户粗框能否通过 4 槽位验证
                val fallbackCalib = Calibration(
                    scaleX = setup.userFrame.estimatedScaleX,
                    scaleY = setup.userFrame.estimatedScaleX,
                    originX = setup.userFrame.left - setup.userFrame.estimatedScaleX * Snapper.REF_FRAME_LEFT,
                    originY = setup.userFrame.top - setup.userFrame.estimatedScaleX * Snapper.REF_FRAME_TOP
                )
                val verifiedCalib = when {
                    calibrator.verifySlots(fullImage, measured.calibration) -> measured.calibration
                    calibrator.verifySlots(fullImage, fallbackCalib) -> fallbackCalib
                    else -> null
                }
                if (verifiedCalib != null) {
                    _uiState.update { current ->
                        current.copy(
                            manualSetup = current.manualSetup?.copy(
                                isSnapping = false,
                                snappedCalibration = verifiedCalib,
                                snapError = null
                            )
                        )
                    }
                } else {
                    _uiState.update { current ->
                        current.copy(
                            manualSetup = current.manualSetup?.copy(
                                isSnapping = false,
                                snappedCalibration = null,
                                snapError = "未检测到有效因子，请确认已打开挑战因子开关并将框拖到 4 个方格上"
                            )
                        )
                    }
                }
            }
        }
    }

    /** 手动引导完成，跳转到确认预览界面。 */
    fun proceedToConfirmPreviewFromManual() {
        val setup = _uiState.value.manualSetup ?: return
        val calib = setup.snappedCalibration ?: Calibration(
            scaleX = setup.userFrame.estimatedScaleX,
            scaleY = setup.userFrame.estimatedScaleX,
            originX = setup.userFrame.left - setup.userFrame.estimatedScaleX * Snapper.REF_FRAME_LEFT,
            originY = setup.userFrame.top - setup.userFrame.estimatedScaleX * Snapper.REF_FRAME_TOP
        )
        val tapRefX = calib.toReferenceX(setup.tapScreenX.toDouble())
        val tapRefY = calib.toReferenceY(setup.tapScreenY.toDouble())

        showConfirmPreview(
            screenshot = setup.screenshot,
            calibration = calib,
            source = CalibrationProfile.Source.MANUAL,
            tapX = tapRefX,
            tapY = tapRefY,
            startAfterConfirm = setup.startAfterConfirm
        )
    }

    /** 退出全屏引导，返回主面板。 */
    fun cancelManualSetup() {
        _uiState.update {
            it.copy(
                mode = OverlayMode.PANEL,
                manualSetup = null,
                confirmPreview = null,
                isBusyCalibrating = false
            )
        }
    }

    /** 确认预览：用户点击「对，保存档案」。 */
    fun onConfirmPreviewAccept() {
        val preview = _uiState.value.confirmPreview ?: return
        val calib = preview.calibration
        val bitmap = preview.screenshot

        viewModelScope.launch(Dispatchers.Default) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val image = ArgbImage(w, h, pixels)
            val patch = ProfileVerifier.captureTogglePatch(image, calib) ?: return@launch

            val profile = CalibrationProfile(
                screenWidth = w,
                screenHeight = h,
                scaleX = calib.scaleX,
                scaleY = calib.scaleY,
                originX = calib.originX,
                originY = calib.originY,
                tapX = preview.tapX,
                tapY = preview.tapY,
                toggleOnPatch = patch,
                source = preview.source,
                createdAt = System.currentTimeMillis()
            )
            calibrationProfileDataStore.saveProfile(profile)

            _uiState.update {
                it.copy(
                    confirmPreview = null,
                    manualSetup = null,
                    hasProfileForCurrentScreen = true,
                    forbiddenZone = computeForbiddenZone(calib, dpToPx(24))
                )
            }

            if (preview.startAfterConfirm) {
                // UI 动画避让（军师架构建议 2026-11-08）：
                // 给全屏悬浮窗退场与小球展示留出 150ms 沉淀窗口，避免过渡帧残留污染首帧识别
                kotlinx.coroutines.delay(150)
                val recipe = _uiState.value.selectedRecipe
                if (recipe != null) {
                    startEngineWithProfile(recipe, profile)
                } else {
                    _uiState.update { it.copy(mode = OverlayMode.PANEL) }
                }
            } else {
                _uiState.update { it.copy(mode = OverlayMode.PANEL) }
            }
        }
    }

    /** 确认预览：用户点击「不对，重新设置」。 */
    fun onConfirmPreviewReject() {
        viewModelScope.launch(Dispatchers.Default) {
            enterManualSetup(startAfterConfirm = _uiState.value.confirmPreview?.startAfterConfirm ?: false)
        }
    }

    /**
     * 保存当前画面到应用目录（架构方案第 3.1、8 节「保存当前画面」功能，方便用户真机导出排查问题）。
     */
    fun saveCurrentScreenShot(onSaved: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val frame = captureCleanFrame() ?: return@launch
            try {
                val bitmap = (frame as? A11yScreenCapturer.BitmapFrame)?.bitmap
                if (bitmap != null) {
                    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "user_captures")
                    dir.mkdirs()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = File(dir, "kaze_factor_$stamp.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    withContext(Dispatchers.Main) {
                        onSaved(file.absolutePath)
                    }
                }
            } finally {
                frame.close()
            }
        }
    }

    // ---- 内部私有方法 ----

    private fun showConfirmPreview(
        screenshot: Bitmap,
        calibration: Calibration,
        source: CalibrationProfile.Source,
        tapX: Double,
        tapY: Double,
        startAfterConfirm: Boolean
    ) {
        val recognizer = recognizerFactory.create(calibration)
        val w = screenshot.width
        val h = screenshot.height
        val pixels = IntArray(w * h)
        screenshot.getPixels(pixels, 0, w, 0, 0, w, h)
        val fullImage = ArgbImage(w, h, pixels)

        val frameResult = recognizer.recognize(fullImage)
        val rects = (1..ReferenceLayout.SLOT_COUNT).map { recognizer.searchRect(it) }
        val recognized: List<Factor?> = frameResult.slots.map { slotRes ->
            when (slotRes) {
                is SlotResult.Factor -> factorDatabase.getFactorById(slotRes.factorId)
                is SlotResult.Ambiguous -> slotRes.candidateIds.firstOrNull()?.let { factorDatabase.getFactorById(it) }
                else -> null
            }
        }

        _uiState.update {
            it.copy(
                mode = OverlayMode.CONFIRM_PREVIEW,
                isBusyCalibrating = false,
                statusMessage = null,
                confirmPreview = ConfirmPreviewData(
                    screenshot = screenshot,
                    calibration = calibration,
                    source = source,
                    tapX = tapX,
                    tapY = tapY,
                    slotRects = rects,
                    slotResults = frameResult.slots,
                    recognizedFactors = recognized,
                    startAfterConfirm = startAfterConfirm
                )
            )
        }
    }

    private fun startEngineWithProfile(recipe: Recipe, profile: CalibrationProfile) {
        val pref = _uiState.value
        val expected = pref.expectedRefreshCount
        val enginePref = EngineSettingsPref(
            frequencyPerSecond = pref.frequencyPerSecond,
            maxCountLimitEnabled = pref.maxCountLimitEnabled,
            customMaxCount = 0,
            vibrateOnHit = pref.vibrateOnHit
        )
        val settings = enginePref.toRefreshSettings(expected)

        _uiState.update {
            it.copy(
                mode = OverlayMode.RUNNING,
                isBusyCalibrating = false,
                statusMessage = null,
                pauseReason = null,
                refreshCount = 0,
                failureCount = 0,
                forbiddenZone = computeForbiddenZone(profile.toCalibration(), dpToPx(24))
            )
        }
        refreshTimestamps.clear()
        lastObservedCount = 0

        engineJob = viewModelScope.launch(Dispatchers.Default) {
            refreshEngine.start(recipe, settings, profile)
        }
    }

    private fun handleEngineState(state: EngineState) {
        when (state) {
            is EngineState.Idle -> {
                _uiState.update { it.copy(isBusyCalibrating = false) }
            }
            is EngineState.Calibrating -> {
                _uiState.update { it.copy(isBusyCalibrating = true, statusMessage = "正在校准…") }
            }
            is EngineState.Running -> {
                updateSpeedStats(state.refreshCount)
                _uiState.update {
                    it.copy(
                        mode = OverlayMode.RUNNING,
                        isBusyCalibrating = false,
                        refreshCount = state.refreshCount,
                        failureCount = state.failureCount,
                        pauseReason = null
                    )
                }
            }
            is EngineState.Hit -> {
                if (_uiState.value.vibrateOnHit) {
                    triggerShortVibration()
                }
                val factors = state.factorIds.mapNotNull { factorDatabase.getFactorById(it) }
                _uiState.update {
                    it.copy(
                        mode = OverlayMode.HIT,
                        refreshCount = state.refreshCount,
                        hitFactors = factors,
                        pauseReason = null
                    )
                }
            }
            is EngineState.Paused -> {
                _uiState.update {
                    it.copy(
                        mode = OverlayMode.RUNNING,
                        refreshCount = state.refreshCount,
                        pauseReason = state.reason
                    )
                }
            }
            is EngineState.Stopped -> {
                _uiState.update {
                    it.copy(isBusyCalibrating = false, actualSpeedPerSec = 0f)
                }
            }
        }
    }

    private fun updateSpeedStats(count: Int) {
        val now = SystemClock.elapsedRealtime()
        if (count > lastObservedCount) {
            refreshTimestamps.addLast(now)
            while (refreshTimestamps.size > 10) {
                refreshTimestamps.removeFirst()
            }
            lastObservedCount = count
            if (refreshTimestamps.size >= 2) {
                val spanSec = (refreshTimestamps.last() - refreshTimestamps.first()) / 1000f
                if (spanSec > 0.05f) {
                    val speed = (refreshTimestamps.size - 1) / spanSec
                    _uiState.update { it.copy(actualSpeedPerSec = speed) }
                }
            }
        }
    }

    private fun triggerShortVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(120)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).roundToInt()
}
