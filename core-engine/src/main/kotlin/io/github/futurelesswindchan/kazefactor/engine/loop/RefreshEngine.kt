package io.github.futurelesswindchan.kazefactor.engine.loop

import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibration
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfile
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibrator
import io.github.futurelesswindchan.kazefactor.engine.vision.DetailedFrameAnalysis
import io.github.futurelesswindchan.kazefactor.engine.vision.FrameAnalyzer
import io.github.futurelesswindchan.kazefactor.engine.vision.ProfileVerifier
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import io.github.futurelesswindchan.kazefactor.engine.vision.ScreenState
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * 自动刷新状态机（架构方案第 4 节），对外通过 [state] 推送 [EngineState]。
 *
 * [start] 和 [resume] 都会挂起，直到命中、暂停或被 [stop] 取消。调用方应在 `Dispatchers.Default`
 * 的协程里调用，识别不占主线程；[pause]、[stop] 可以从任意线程调用。
 *
 * 位置来源：
 * - 传入位置档案：先快速核验（[ProfileVerifier]）。通过后的**第一帧**有槽位是 `Unknown` 时，
 *   视为核验失败（第 3.4.5 节第 2 条），暂停为 [PauseReason.VERIFY_FAILED]。核验帧只比较开关区域，
 *   第一帧才做正式识别，所以同一帧不会识别两次。
 * - 没有档案：自动校准，最多 [MAX_CALIBRATION_ATTEMPTS] 帧。
 *
 * NOTE: 快速核验失败时只暂停为 [PauseReason.VERIFY_FAILED]，不在引擎里自动校准（架构设计方 2026-11-07 确认）。
 * 第 3.4.6 节要求因出错重新设置位置时必须让用户确认预览，这一步只有界面能做，
 * 所以由 app 完成校准和预览、保存新档案后重新 [start]。
 *
 * ```
 * val engine = RefreshEngine(capturer, tapper, analyzer, Decision(db), calibrator, verifier)
 * scope.launch(Dispatchers.Default) { engine.start(recipe, settings, profile) }
 * // 开关关闭暂停后，玩家打开开关、点「继续」：
 * scope.launch(Dispatchers.Default) { engine.resume() }
 * ```
 *
 * @param capturer 截图器
 * @param tapper 点击器
 * @param analyzer 每帧分析
 * @param decision 配方判定
 * @param calibrator 没有档案时的自动校准
 * @param verifier 有档案时的快速核验
 * @param timeProvider 当前时间（毫秒），测试中换成虚拟时间
 * @param delayProvider 挂起等待，测试中可替换
 * @param random 点击位置的随机偏移，测试中可固定种子
 */
class RefreshEngine(
    private val capturer: ScreenCapturer,
    private val tapper: Tapper,
    private val analyzer: FrameAnalyzer,
    private val decision: Decision,
    private val calibrator: Calibrator,
    private val verifier: ProfileVerifier,
    private val timeProvider: () -> Long = System::currentTimeMillis,
    private val delayProvider: suspend (Long) -> Unit = { delay(it.milliseconds) },
    private val random: Random = Random.Default
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    /** 对外的只读状态。 */
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * 每帧分析完成后的可选回调，供上层诊断黑匣子收集现场快照。
     *
     * 当设置了此回调时，循环内部自动调用 [FrameAnalyzer.analyzeDetailed] 或 [FrameAnalyzer.analyzeDetailedWithProfile]，
     * 并在 [Frame.close] 之前将原始帧、详细分析结果与当前坐标换算传给监听方。
     */
    @Volatile
    var onFrameAnalyzed: ((frame: Frame, detailed: DetailedFrameAnalysis, calibration: Calibration) -> Unit)? = null

    /** 一次运行的不变参数，[resume] 时沿用。 */
    private class RunContext(
        val recipe: Recipe,
        val settings: RefreshSettings,
        val profile: CalibrationProfile?,
        val calibration: Calibration
    )

    @Volatile
    private var runJob: Job? = null

    /** 最近一次校准成功的运行参数；校准或核验失败时为 null，此时不能 [resume]。 */
    @Volatile
    private var lastRun: RunContext? = null

    /**
     * 用户请求暂停。用标志位而不是直接改 [state]，避免循环里随后写入的 Running 把暂停覆盖掉。
     */
    @Volatile
    private var pauseRequested = false

    /**
     * 开始一次新的自动刷新：取得位置（核验或校准）后进入循环，统计从 0 开始。
     *
     * 只能在没有运行中的循环时调用（[EngineState.Idle]、[EngineState.Stopped]、[EngineState.Paused]、
     * [EngineState.Hit]）。从暂停状态调用表示「重新开始」，例如 app 重新设置位置后带着新档案再来一次。
     *
     * @param recipe 目标配方
     * @param settings 运行参数
     * @param profile 位置档案，为 null 时做自动校准
     * @throws IllegalStateException 已有循环在运行时
     */
    suspend fun start(recipe: Recipe, settings: RefreshSettings, profile: CalibrationProfile? = null) {
        launchRun {
            lastRun = null
            val calibration = resolveCalibration(profile) ?: return@launchRun
            val ctx = RunContext(recipe, settings, profile, calibration)
            lastRun = ctx
            runLoop(ctx, initialRefreshCount = 0, checkFirstFrame = profile != null)
        }
    }

    /**
     * 从暂停处继续，沿用上次的配方、参数和位置，不重新校准（第 4 节第 4 条）。
     *
     * 恢复后先截图识别、判断是否命中，再决定点不点（第 4 节第 4 条最后一点），
     * 所以玩家打开开关时游戏顺带刷出的目标组合不会被刷掉。已刷新次数从暂停时的值继续累计。
     *
     * @throws IllegalStateException 当前不是 [EngineState.Paused]、已有循环在运行，
     *   或上次没有成功取得位置（校准失败、核验失败，此时应重新 [start]）
     */
    suspend fun resume() {
        val paused = _state.value as? EngineState.Paused
            ?: throw IllegalStateException("只能从 Paused 恢复，当前 ${_state.value}")
        val ctx = lastRun
            ?: throw IllegalStateException("上次没有成功取得位置（${paused.reason}），请重新 start")
        launchRun { runLoop(ctx, initialRefreshCount = paused.refreshCount, checkFirstFrame = false) }
    }

    /** 用户主动暂停（仅在 [EngineState.Running] 时有效），循环在下一帧开始前停下。 */
    fun pause() {
        if (_state.value is EngineState.Running) pauseRequested = true
    }

    /** 从任意状态停止，取消运行中的循环。 */
    fun stop() {
        runJob?.cancel()
        runJob = null
        lastRun = null
        _state.value = EngineState.Stopped
    }

    // ---- 内部逻辑 ----

    /** 在子协程里跑 [block] 并等待结束；被 [stop] 取消时以 Stopped 收尾。 */
    private suspend fun launchRun(block: suspend () -> Unit) {
        check(runJob?.isActive != true) { "已有刷新循环在运行" }
        pauseRequested = false
        coroutineScope {
            val job = launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    // 取消前循环可能刚写过 Running，这里统一收尾为 Stopped
                    _state.value = EngineState.Stopped
                    throw e
                }
            }
            runJob = job
            job.join()
        }
    }

    /**
     * 截图 → 识别 → 决策循环。
     *
     * @param initialRefreshCount 已刷新次数的起点，[resume] 时沿用暂停前的值
     * @param checkFirstFrame 为 true 时，第一帧有 `Unknown` 槽位就判为核验失败（快速核验通过后的检查）
     */
    private suspend fun runLoop(ctx: RunContext, initialRefreshCount: Int, checkFirstFrame: Boolean) {
        val settings = ctx.settings
        var refreshCount = initialRefreshCount
        var consecutiveFailures = 0
        var lastFactorIds: List<Int>? = null // 恢复后为 null，第一帧不会被当成「点击无效」
        var sameResultCount = 0
        var firstFramePending = checkFirstFrame

        _state.value = EngineState.Running(refreshCount)

        while (true) {
            currentCoroutineContext().ensureActive()
            if (pauseRequested) {
                pauseRequested = false
                _state.value = EngineState.Paused(PauseReason.USER_PAUSED, refreshCount)
                return
            }

            val intervalMs = maxOf(settings.userIntervalMs, capturer.minIntervalMs)
            val captureStart = timeProvider()

            val frame = capturer.capture()
            if (frame == null) {
                // 截图失败与识别失败一样计数，不点击
                consecutiveFailures++
                if (consecutiveFailures >= settings.maxConsecutiveFailures) {
                    _state.value = EngineState.Paused(PauseReason.CONSECUTIVE_FAILURES, refreshCount)
                    return
                }
                waitForInterval(captureStart, intervalMs)
                continue
            }

            try {
                val image = frame.crop(0, 0, frame.width, frame.height)
                val listener = onFrameAnalyzed
                val detailed = if (listener != null) {
                    if (ctx.profile != null) {
                        analyzer.analyzeDetailedWithProfile(image, ctx.profile)
                    } else {
                        analyzer.analyzeDetailed(image, ctx.calibration)
                    }
                } else {
                    null
                }
                val analysis = detailed?.analysis ?: if (ctx.profile != null) {
                    analyzer.analyzeWithProfile(image, ctx.profile)
                } else {
                    analyzer.analyze(image, ctx.calibration)
                }
                if (detailed != null && listener != null) {
                    listener.invoke(frame, detailed, ctx.calibration)
                }

                if (firstFramePending) {
                    firstFramePending = false
                    if (analysis.slotResults.any { it == SlotResult.Unknown }) {
                        lastRun = null // 位置不可信，不允许 resume
                        _state.value = EngineState.Paused(PauseReason.VERIFY_FAILED, refreshCount)
                        return
                    }
                }

                when (analysis.screenState) {
                    ScreenState.NOT_FACTOR_SCREEN -> {
                        _state.value = EngineState.Paused(PauseReason.NOT_FACTOR_SCREEN, refreshCount)
                        return
                    }
                    ScreenState.FACTOR_SCREEN_OFF -> {
                        _state.value = EngineState.Paused(PauseReason.TOGGLE_OFF, refreshCount)
                        return
                    }
                    ScreenState.FACTOR_SCREEN_ON -> when (val result = decision.decide(analysis.slotResults, ctx.recipe)) {
                        is DecisionResult.Hit -> {
                            _state.value = EngineState.Hit(result.factorIds, refreshCount)
                            return
                        }
                        is DecisionResult.Uncertain -> {
                            consecutiveFailures++
                            if (consecutiveFailures >= settings.maxConsecutiveFailures) {
                                _state.value = EngineState.Paused(PauseReason.CONSECUTIVE_FAILURES, refreshCount)
                                return
                            }
                            _state.value = EngineState.Running(refreshCount, consecutiveFailures)
                        }
                        is DecisionResult.Miss -> {
                            if (result.factorIds == lastFactorIds) {
                                consecutiveFailures = 0
                                // 与上次完全相同：游戏可能还没刷新这一帧，只重新截图不点击（第 4 节第 2 条）
                                sameResultCount++
                                if (sameResultCount >= settings.maxSameResultCount) {
                                    _state.value = EngineState.Paused(PauseReason.TAP_INEFFECTIVE, refreshCount)
                                    return
                                }
                            } else {
                                // 上限在「识别完、点击前」检查：最后一次刷新的结果一定会被识别，命中不会漏掉
                                val max = settings.maxRefreshCount
                                if (max != null && refreshCount >= max) {
                                    _state.value = EngineState.Paused(PauseReason.MAX_REFRESH_COUNT, refreshCount)
                                    return
                                }
                                if (tapRefreshButton(ctx)) {
                                    consecutiveFailures = 0
                                    lastFactorIds = result.factorIds
                                    sameResultCount = 1
                                    refreshCount++
                                    _state.value = EngineState.Running(refreshCount, consecutiveFailures)
                                } else {
                                    // 系统拒绝手势（如无障碍手势被取消）归入连续失败（架构设计方 2026-11-07 拍板）
                                    consecutiveFailures++
                                    if (consecutiveFailures >= settings.maxConsecutiveFailures) {
                                        _state.value = EngineState.Paused(PauseReason.CONSECUTIVE_FAILURES, refreshCount)
                                        return
                                    }
                                    _state.value = EngineState.Running(refreshCount, consecutiveFailures)
                                }
                            }
                        }
                    }
                }
            } finally {
                frame.close()
            }

            waitForInterval(captureStart, intervalMs)
        }
    }

    /** 点击刷新按钮，坐标在 ±10 px 内随机偏移（架构方案第 5 节）。 */
    private suspend fun tapRefreshButton(ctx: RunContext): Boolean {
        val x = ctx.profile?.tapScreenX() ?: ctx.calibration.toScreenX(ReferenceLayout.REFRESH_TAP_X.toDouble())
        val y = ctx.profile?.tapScreenY() ?: ctx.calibration.toScreenY(ReferenceLayout.REFRESH_TAP_Y.toDouble())
        return tapper.tap(
            (x + random.nextDouble(-TAP_JITTER_PX, TAP_JITTER_PX)).toFloat(),
            (y + random.nextDouble(-TAP_JITTER_PX, TAP_JITTER_PX)).toFloat()
        )
    }

    /**
     * 取得位置：有档案走快速核验，没档案走自动校准。
     *
     * @return 换算；失败时已设好 [EngineState.Paused] 并返回 null
     */
    private suspend fun resolveCalibration(profile: CalibrationProfile?): Calibration? {
        _state.value = EngineState.Calibrating
        if (profile == null) return autoCalibrate()

        val frame = capturer.capture() ?: run {
            _state.value = EngineState.Paused(PauseReason.VERIFY_FAILED)
            return null
        }
        try {
            return when (verifier.verify(frame.crop(0, 0, frame.width, frame.height), profile)) {
                is ProfileVerifier.Result.Pass -> profile.toCalibration()
                is ProfileVerifier.Result.ToggleOff -> {
                    _state.value = EngineState.Paused(PauseReason.TOGGLE_OFF)
                    null
                }
                is ProfileVerifier.Result.Fail -> {
                    _state.value = EngineState.Paused(PauseReason.VERIFY_FAILED)
                    null
                }
            }
        } finally {
            frame.close()
        }
    }

    /** 自动校准：最多截 [MAX_CALIBRATION_ATTEMPTS] 帧，全部失败时暂停为 [PauseReason.CALIBRATION_FAILED]。 */
    private suspend fun autoCalibrate(): Calibration? {
        repeat(MAX_CALIBRATION_ATTEMPTS) {
            val frame = capturer.capture() ?: return@repeat
            try {
                calibrator.calibrate(frame.crop(0, 0, frame.width, frame.height))?.let { return it }
            } finally {
                frame.close()
            }
        }
        _state.value = EngineState.Paused(PauseReason.CALIBRATION_FAILED)
        return null
    }

    /** 等到本帧开始后满 [intervalMs] 毫秒；识别本身已经超时则不等。 */
    private suspend fun waitForInterval(startMs: Long, intervalMs: Long) {
        val remaining = intervalMs - (timeProvider() - startMs)
        if (remaining > 0) delayProvider(remaining)
    }

    companion object {
        /** 自动校准最多尝试的帧数（第 3.1 节「验证与重试」）。 */
        const val MAX_CALIBRATION_ATTEMPTS = 3

        /** 点击位置随机偏移的半径（屏幕像素）。 */
        const val TAP_JITTER_PX = 10.0
    }
}
