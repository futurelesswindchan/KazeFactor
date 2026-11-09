package io.github.futurelesswindchan.kazefactor.engine.loop

import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibration
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfile
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibrator
import io.github.futurelesswindchan.kazefactor.engine.vision.DetailedFrameAnalysis
import io.github.futurelesswindchan.kazefactor.engine.vision.FrameAnalysis
import io.github.futurelesswindchan.kazefactor.engine.vision.FrameAnalyzer
import io.github.futurelesswindchan.kazefactor.engine.vision.ProfileVerifier
import io.github.futurelesswindchan.kazefactor.engine.vision.ScreenState
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [RefreshEngine] 的单元测试（架构方案第 9 节第 4 行验收条目）。
 *
 * 假截图器按预设序列返回帧，假点击器记录点击时刻，[FrameAnalyzer] 按脚本返回预设结果，不跑真实识别。
 * 时间用 `runTest` 的虚拟时间：引擎的 `delay` 不真正等待，`testScheduler.currentTime` 作为时钟。
 *
 * 因子 key 来自 `factors.json`，类别组合对应 17 种合法模式之一：
 * - combo1：PATTERN_3 大恶 + 中恶 + 大良 + 大良 = double_joy + weapon_overheating + infinite_energy + gigantic_pet
 * - combo2：PATTERN_2 大恶 + 中恶 + 小良 + 大良 = double_joy + weapon_overheating + good_luck + gigantic_pet
 * - combo3：PATTERN_3 大恶 + 中恶 + 大良 + 大良 = double_joy + weapon_overheating + sales_day + gigantic_pet
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshEngineTest {

    private val db = TestFixtures.database

    private fun id(key: String): Int = db.getFactorByKey(key)?.id ?: error("未知因子 key: $key")

    // ---- 预设分析结果 ----

    private fun onScreen(s1: String, s2: String, s3: String, s4: String) = FrameAnalysis(
        ScreenState.FACTOR_SCREEN_ON,
        listOf(SlotResult.Factor(id(s1)), SlotResult.Factor(id(s2)), SlotResult.Factor(id(s3)), SlotResult.Factor(id(s4)))
    )

    private val offScreen = FrameAnalysis(ScreenState.FACTOR_SCREEN_OFF, List(4) { SlotResult.Question })
    private val notFactor = FrameAnalysis(ScreenState.NOT_FACTOR_SCREEN, List(4) { SlotResult.Unknown })

    /** 有一个 Unknown 槽位，Decision 判为 UNCERTAIN。 */
    private val uncertain = FrameAnalysis(
        ScreenState.FACTOR_SCREEN_ON,
        listOf(SlotResult.Factor(id("double_joy")), SlotResult.Unknown,
            SlotResult.Factor(id("infinite_energy")), SlotResult.Factor(id("gigantic_pet")))
    )

    /** 开关开但有一个槽位是问号（过渡帧），Decision 判为 UNCERTAIN。 */
    private val questionOnScreen = FrameAnalysis(
        ScreenState.FACTOR_SCREEN_ON,
        listOf(SlotResult.Factor(id("double_joy")), SlotResult.Factor(id("weapon_overheating")),
            SlotResult.Question, SlotResult.Factor(id("gigantic_pet")))
    )

    private val combo1 = onScreen("double_joy", "weapon_overheating", "infinite_energy", "gigantic_pet")
    private val combo2 = onScreen("double_joy", "weapon_overheating", "good_luck", "gigantic_pet")
    private val combo3 = onScreen("double_joy", "weapon_overheating", "sales_day", "gigantic_pet")

    /** 想要 infinite_energy：combo1 命中，combo2、combo3 不命中。 */
    private val recipe = Recipe(id = "test", name = "T", wantedFactorIds = setOf(id("infinite_energy")))

    /** 空配方：任何合法组合都命中。 */
    private val emptyRecipe = Recipe(id = "test-empty", name = "T", wantedFactorIds = emptySet())

    private val settings = RefreshSettings(frequencyPerSecond = 10, maxConsecutiveFailures = 3, maxRefreshCount = 100)

    private val dummyImage = ArgbImage(1, 1, intArrayOf(0))

    /** combo2、combo3 交替的「永远不命中」序列，不会触发「点击无效」。 */
    private fun alternatingMisses(n: Int) = List(n) { if (it % 2 == 0) combo2 else combo3 }

    // ---- 假实现 ----

    private class FakeFrame(private val image: ArgbImage) : Frame {
        var closed = false; private set
        override val width get() = image.width
        override val height get() = image.height
        override fun crop(x: Int, y: Int, w: Int, h: Int) = image
        override fun close() { closed = true }
    }

    /** 按顺序返回 [frameCount] 帧，用完后返回 null（截图失败）。 */
    private inner class FakeCapturer(private val frameCount: Int, override val minIntervalMs: Long) : ScreenCapturer {
        val frames = mutableListOf<FakeFrame>()
        override suspend fun capture(): Frame? {
            if (frames.size >= frameCount) return null
            return FakeFrame(dummyImage).also { frames += it }
        }
    }

    /** 记录每次点击的坐标和虚拟时刻。 */
    private class FakeTapper(private val clock: () -> Long, var acceptTap: Boolean = true) : Tapper {
        val taps = mutableListOf<Pair<Float, Float>>()
        val tapTimes = mutableListOf<Long>()
        override suspend fun tap(x: Float, y: Float): Boolean {
            taps += x to y
            tapTimes += clock()
            return acceptTap
        }
    }

    /** 按脚本返回预设的分析结果；脚本用完后返回 NOT_FACTOR_SCREEN。 */
    private class FakeAnalyzer(private val results: List<FrameAnalysis>) : FrameAnalyzer(TestFixtures.recognizerFactory) {
        private var index = 0
        override fun analyze(frame: ArgbImage, calibration: Calibration): FrameAnalysis =
            results.getOrElse(index++) { NOT_FACTOR_SCREEN_RESULT }
        override fun analyzeWithProfile(frame: ArgbImage, profile: CalibrationProfile): FrameAnalysis =
            analyze(frame, Calibration.IDENTITY)
        override fun analyzeDetailed(frame: ArgbImage, calibration: Calibration): DetailedFrameAnalysis =
            DetailedFrameAnalysis(analyze(frame, calibration), emptyList(), null)
        override fun analyzeDetailedWithProfile(frame: ArgbImage, profile: CalibrationProfile): DetailedFrameAnalysis =
            DetailedFrameAnalysis(analyzeWithProfile(frame, profile), emptyList(), 0.0)
    }

    private class FakeCalibrator : Calibrator(TestFixtures.helpIconTemplate, TestFixtures.recognizerFactory) {
        override fun calibrate(frame: ArgbImage): Calibration = Calibration.IDENTITY
    }

    private class FakeVerifier(private val result: Result = Result.Pass(0.0)) : ProfileVerifier(TestFixtures.recognizerFactory) {
        override fun verify(frame: ArgbImage, profile: CalibrationProfile) = result
    }

    private class Fixture(val engine: RefreshEngine, val tapper: FakeTapper, val capturer: FakeCapturer)

    /**
     * 组装引擎。第 1 帧被校准（或快速核验）消费，之后每帧对应 [analyses] 里的一项。
     */
    private fun TestScope.fixture(
        analyses: List<FrameAnalysis>,
        frameCount: Int = analyses.size + 1,
        captureMinMs: Long = 0,
        verifier: ProfileVerifier = FakeVerifier()
    ): Fixture {
        val clock = { testScheduler.currentTime }
        val capturer = FakeCapturer(frameCount, captureMinMs)
        val tapper = FakeTapper(clock)
        val engine = RefreshEngine(
            capturer, tapper, FakeAnalyzer(analyses), Decision(db), FakeCalibrator(), verifier,
            timeProvider = clock
        )
        return Fixture(engine, tapper, capturer)
    }

    private val fakeProfile = CalibrationProfile.create(
        1, 1, Calibration.IDENTITY, ArgbImage(1, 1, intArrayOf(0)), CalibrationProfile.Source.AUTO, 0L
    )

    // ==================== 验收用例 ====================

    @Test
    fun `命中后停在 Hit 且不点击`() = runTest {
        val f = fixture(listOf(combo1))
        f.engine.start(recipe, settings)
        val s = assertIs<EngineState.Hit>(f.engine.state.value)
        assertTrue(id("infinite_energy") in s.factorIds)
        assertEquals(0, s.refreshCount)
        assertTrue(f.tapper.taps.isEmpty())
    }

    @Test
    fun `未命中时点击刷新`() = runTest {
        val f = fixture(listOf(combo2, combo1))
        f.engine.start(recipe, settings)
        assertEquals(1, assertIs<EngineState.Hit>(f.engine.state.value).refreshCount)
        assertEquals(1, f.tapper.taps.size)
    }

    @Test
    fun `点击坐标在刷新按钮 ±10 px 内`() = runTest {
        val f = fixture(alternatingMisses(20) + combo1)
        f.engine.start(recipe, settings)
        assertEquals(20, f.tapper.taps.size)
        f.tapper.taps.forEach { (x, y) ->
            assertTrue(x in 1367f..1387f && y in 423f..443f, "点击 ($x, $y) 超出 (1377, 433) ±10")
        }
    }

    @Test
    fun `UNCERTAIN 不点击并重新截图`() = runTest {
        val f = fixture(listOf(uncertain, combo1))
        f.engine.start(recipe, settings)
        assertEquals(0, assertIs<EngineState.Hit>(f.engine.state.value).refreshCount)
        assertTrue(f.tapper.taps.isEmpty())
    }

    @Test
    fun `连续 N 次 UNCERTAIN 暂停`() = runTest {
        val f = fixture(List(3) { uncertain } + combo1)
        f.engine.start(recipe, settings.copy(maxConsecutiveFailures = 3))
        assertEquals(PauseReason.CONSECUTIVE_FAILURES, assertIs<EngineState.Paused>(f.engine.state.value).reason)
        assertTrue(f.tapper.taps.isEmpty())
    }

    @Test
    fun `UNCERTAIN 未满 N 次时计数被 MISS 清零`() = runTest {
        // 2 次失败 → MISS 清零 → 再 2 次失败 → 命中；N = 3 时不应暂停
        val f = fixture(listOf(uncertain, uncertain, combo2, uncertain, uncertain, combo1))
        f.engine.start(recipe, settings.copy(maxConsecutiveFailures = 3))
        assertIs<EngineState.Hit>(f.engine.state.value)
    }

    @Test
    fun `截图失败也计入连续失败`() = runTest {
        // 只有校准用的 1 帧，之后截图全部返回 null
        val f = fixture(emptyList(), frameCount = 1)
        f.engine.start(recipe, settings.copy(maxConsecutiveFailures = 3))
        assertEquals(PauseReason.CONSECUTIVE_FAILURES, assertIs<EngineState.Paused>(f.engine.state.value).reason)
    }

    @Test
    fun `手势点击被系统拒绝也计入连续失败`() = runTest {
        val f = fixture(List(4) { combo2 })
        f.tapper.acceptTap = false
        f.engine.start(recipe, settings.copy(maxConsecutiveFailures = 3))
        val paused = assertIs<EngineState.Paused>(f.engine.state.value)
        assertEquals(PauseReason.CONSECUTIVE_FAILURES, paused.reason)
        assertEquals(0, paused.refreshCount)
        assertEquals(3, f.tapper.taps.size)
    }

    @Test
    fun `点击后结果不变只重截不再点击，连续 3 次相同暂停`() = runTest {
        val f = fixture(List(4) { combo2 })
        f.engine.start(recipe, settings.copy(maxSameResultCount = 3))
        assertEquals(PauseReason.TAP_INEFFECTIVE, assertIs<EngineState.Paused>(f.engine.state.value).reason)
        assertEquals(1, f.tapper.taps.size)
    }

    @Test
    fun `结果相同一次后变化，照常继续`() = runTest {
        // combo2 → 点击 → combo2（游戏还没刷新，不点）→ combo1 命中
        val f = fixture(listOf(combo2, combo2, combo1))
        f.engine.start(recipe, settings)
        assertEquals(1, assertIs<EngineState.Hit>(f.engine.state.value).refreshCount)
        assertEquals(1, f.tapper.taps.size)
    }

    @Test
    fun `达到次数上限暂停`() = runTest {
        val f = fixture(alternatingMisses(8))
        f.engine.start(recipe, settings.copy(maxRefreshCount = 2))
        val s = assertIs<EngineState.Paused>(f.engine.state.value)
        assertEquals(PauseReason.MAX_REFRESH_COUNT, s.reason)
        assertEquals(2, s.refreshCount)
        assertEquals(2, f.tapper.taps.size)
    }

    @Test
    fun `最后一次刷新的结果仍会被识别，命中不会漏掉`() = runTest {
        // 上限 2：点 2 次后刷出 combo1，必须判为命中而不是因上限暂停
        val f = fixture(listOf(combo2, combo3, combo1))
        f.engine.start(recipe, settings.copy(maxRefreshCount = 2))
        assertEquals(2, assertIs<EngineState.Hit>(f.engine.state.value).refreshCount)
    }

    @Test
    fun `开关关闭时暂停不点击`() = runTest {
        val f = fixture(listOf(offScreen))
        f.engine.start(emptyRecipe, settings)
        assertEquals(PauseReason.TOGGLE_OFF, assertIs<EngineState.Paused>(f.engine.state.value).reason)
        assertTrue(f.tapper.taps.isEmpty())
    }

    @Test
    fun `开关开但出现问号按 UNCERTAIN 处理`() = runTest {
        val f = fixture(listOf(questionOnScreen, combo1))
        f.engine.start(recipe, settings)
        assertIs<EngineState.Hit>(f.engine.state.value)
        assertTrue(f.tapper.taps.isEmpty())
    }

    @Test
    fun `不在因子界面时暂停`() = runTest {
        val f = fixture(listOf(notFactor))
        f.engine.start(emptyRecipe, settings)
        assertEquals(PauseReason.NOT_FACTOR_SCREEN, assertIs<EngineState.Paused>(f.engine.state.value).reason)
        assertTrue(f.tapper.taps.isEmpty())
    }

    @Test
    fun `恢复后先判断再点击，次数接着累计`() = runTest {
        // 第 1 轮：combo2 未命中点 1 次 → 开关关闭暂停
        // 玩家打开开关，游戏顺带刷出 combo1；resume 后第一帧就命中，不能再点
        // 多给 1 帧余量：如果 resume 偷偷重新校准，会多截一帧，被最后一条断言抓到
        val f = fixture(listOf(combo2, offScreen, combo1), frameCount = 5)
        f.engine.start(recipe, settings)
        val paused = assertIs<EngineState.Paused>(f.engine.state.value)
        assertEquals(PauseReason.TOGGLE_OFF, paused.reason)
        assertEquals(1, paused.refreshCount)

        f.engine.resume()
        assertEquals(1, assertIs<EngineState.Hit>(f.engine.state.value).refreshCount)
        assertEquals(1, f.tapper.taps.size)
        // 校准 1 帧 + combo2 + offScreen + 恢复后 combo1 = 4 帧；重新校准的话会是 5 帧
        assertEquals(4, f.capturer.frames.size, "恢复时不应重新校准")
    }

    @Test
    fun `快速核验通过后第一帧有 Unknown 判为核验失败`() = runTest {
        val f = fixture(listOf(uncertain, combo1))
        f.engine.start(recipe, settings, fakeProfile)
        assertEquals(PauseReason.VERIFY_FAILED, assertIs<EngineState.Paused>(f.engine.state.value).reason)
        assertTrue(f.tapper.taps.isEmpty())
    }

    @Test
    fun `快速核验判为开关关闭时暂停`() = runTest {
        val f = fixture(listOf(combo1), verifier = FakeVerifier(ProfileVerifier.Result.ToggleOff(40.0)))
        f.engine.start(recipe, settings, fakeProfile)
        assertEquals(PauseReason.TOGGLE_OFF, assertIs<EngineState.Paused>(f.engine.state.value).reason)
    }

    @Test
    fun `快速核验失败时暂停，不在引擎里自动校准`() = runTest {
        val f = fixture(listOf(combo1), verifier = FakeVerifier(ProfileVerifier.Result.Fail(99.0)))
        f.engine.start(recipe, settings, fakeProfile)
        assertEquals(PauseReason.VERIFY_FAILED, assertIs<EngineState.Paused>(f.engine.state.value).reason)
        assertEquals(1, f.capturer.frames.size)
    }

    @Test
    fun `每帧处理完都会关闭`() = runTest {
        val f = fixture(listOf(combo2, uncertain, combo1))
        f.engine.start(recipe, settings)
        assertTrue(f.capturer.frames.all { it.closed })
    }

    @Test
    fun `节拍间隔取用户频率与截图下限的较大值`() = runTest {
        // 频率 10 次/秒（100 ms）但截图下限 333 ms → 点击间隔 333 ms
        val f = fixture(listOf(combo2, combo3, combo2, combo1), captureMinMs = 333)
        f.engine.start(recipe, settings.copy(frequencyPerSecond = 10))
        assertEquals(listOf(0L, 333L, 666L), f.tapper.tapTimes)
    }

    @Test
    fun `用户频率更低时按用户频率`() = runTest {
        // 频率 1 次/秒（1000 ms）大于截图下限 333 ms → 点击间隔 1000 ms
        val f = fixture(listOf(combo2, combo3, combo1), captureMinMs = 333)
        f.engine.start(recipe, settings.copy(frequencyPerSecond = 1))
        assertEquals(listOf(0L, 1000L), f.tapper.tapTimes)
    }

    @Test
    fun `运行中 stop 后停在 Stopped 且不再点击`() = runTest {
        val f = fixture(alternatingMisses(1000))
        val job = launch { f.engine.start(recipe, settings.copy(maxRefreshCount = null)) }
        advanceTimeBy(1_050) // 10 次/秒，约刷了 11 次
        assertIs<EngineState.Running>(f.engine.state.value)
        val tapsBeforeStop = f.tapper.taps.size
        assertTrue(tapsBeforeStop > 0)

        f.engine.stop()
        job.join()
        advanceUntilIdle()
        assertIs<EngineState.Stopped>(f.engine.state.value)
        assertEquals(tapsBeforeStop, f.tapper.taps.size)
    }

    @Test
    fun `用户暂停后可以恢复`() = runTest {
        val f = fixture(alternatingMisses(30) + combo1)
        val job = launch { f.engine.start(recipe, settings.copy(maxRefreshCount = null)) }
        advanceTimeBy(550)
        f.engine.pause()
        job.join()
        val paused = assertIs<EngineState.Paused>(f.engine.state.value)
        assertEquals(PauseReason.USER_PAUSED, paused.reason)
        assertEquals(paused.refreshCount, f.tapper.taps.size)

        f.engine.resume()
        assertEquals(30, assertIs<EngineState.Hit>(f.engine.state.value).refreshCount)
    }

    @Test
    fun `RefreshSettings 间隔和默认上限`() {
        assertEquals(333L, RefreshSettings(frequencyPerSecond = 3).userIntervalMs)
        assertEquals(100L, RefreshSettings(frequencyPerSecond = 10).userIntervalMs)
        assertEquals(100, RefreshSettings.defaultMaxRefreshCount(10.0))
        assertEquals(1752, RefreshSettings.defaultMaxRefreshCount(584.0))
        assertEquals(null, RefreshSettings.defaultMaxRefreshCount(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `onFrameAnalyzed 在帧关闭前回调详细分析结果`() = runTest {
        val f = fixture(listOf(uncertain, uncertain, uncertain))
        var callbackCount = 0
        var allOpenDuringCallback = true
        f.engine.onFrameAnalyzed = { frame, _, _ ->
            callbackCount++
            if ((frame as FakeFrame).closed) {
                allOpenDuringCallback = false
            }
        }
        f.engine.start(recipe, settings.copy(maxConsecutiveFailures = 3))
        assertEquals(3, callbackCount)
        assertTrue(allOpenDuringCallback, "回调触发时 Frame 尚未关闭，确保可安全拷贝位图")
        assertTrue(f.capturer.frames.all { it.closed }, "循环结束后所有 Frame 均已关闭")
    }
}
