package io.github.futurelesswindchan.kazefactor.overlay

import io.github.futurelesswindchan.kazefactor.data.datastore.CalibrationProfileDataStore
import io.github.futurelesswindchan.kazefactor.data.datastore.EngineSettingsPref
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.loop.PauseReason
import io.github.futurelesswindchan.kazefactor.engine.loop.RefreshSettings
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗状态、参数映射、确认预览门控与等比档案校验单元测试。
 */
class OverlayStateAndPrefTest {

    private val dummyFactor = Factor(
        id = 1,
        key = "infinite_energy",
        name = "无限能量",
        nameEn = "Infinite Energy",
        description = "",
        descriptionEn = "",
        category = FactorCategory.MAJOR_GOOD,
        iconFileName = "infinite_energy.png"
    )

    @Test
    fun `EngineSettingsPref toRefreshSettings default max count formula`() {
        val pref = EngineSettingsPref(
            frequencyPerSecond = 5,
            maxCountLimitEnabled = true,
            customMaxCount = 0
        )
        // 期望 11.4 次 → 11.4 * 3 = 34，低于 100 取 100
        val s1 = pref.toRefreshSettings(11.4)
        assertEquals(5, s1.frequencyPerSecond)
        assertEquals(100, s1.maxRefreshCount)

        // 期望 584 次 → 584 * 3 = 1752
        val s2 = pref.toRefreshSettings(584.0)
        assertEquals(1752, s2.maxRefreshCount)
    }

    @Test
    fun `EngineSettingsPref toRefreshSettings disabled or custom max count`() {
        val disabled = EngineSettingsPref(maxCountLimitEnabled = false)
        assertNull(disabled.toRefreshSettings(200.0).maxRefreshCount)

        val custom = EngineSettingsPref(maxCountLimitEnabled = true, customMaxCount = 250)
        assertEquals(250, custom.toRefreshSettings(10.0).maxRefreshCount)
    }

    @Test
    fun `EngineSettingsPref frequency is clamped to 1 to 10`() {
        val low = EngineSettingsPref(frequencyPerSecond = 0).toRefreshSettings(50.0)
        val high = EngineSettingsPref(frequencyPerSecond = 99).toRefreshSettings(50.0)
        assertEquals(1, low.frequencyPerSecond)
        assertEquals(10, high.frequencyPerSecond)
    }

    @Test
    fun `EngineSettingsPref default frequency uses fixed 350ms interval`() {
        val pref = EngineSettingsPref()
        val settings = pref.toRefreshSettings(100.0)
        assertEquals(350L, RefreshSettings.FIXED_INTERVAL_MS)
        assertEquals(RefreshSettings.FIXED_INTERVAL_MS, settings.userIntervalMs)
    }

    @Test
    fun `OverlayUiState isFullScreenMode only true for MANUAL_SETUP and CONFIRM_PREVIEW`() {
        assertFalse(OverlayUiState(mode = OverlayMode.BUBBLE).isFullScreenMode)
        assertFalse(OverlayUiState(mode = OverlayMode.PANEL).isFullScreenMode)
        assertFalse(OverlayUiState(mode = OverlayMode.RUNNING).isFullScreenMode)
        assertFalse(OverlayUiState(mode = OverlayMode.HIT).isFullScreenMode)
        assertTrue(OverlayUiState(mode = OverlayMode.MANUAL_SETUP).isFullScreenMode)
        assertTrue(OverlayUiState(mode = OverlayMode.CONFIRM_PREVIEW).isFullScreenMode)
    }

    @Test
    fun `PauseReason toDisplayMessage returns non-blank message for all reasons`() {
        for (reason in PauseReason.entries) {
            val msg = reason.toDisplayMessage()
            assertTrue("PauseReason $reason should have non-blank display message", msg.isNotBlank())
        }
    }

    @Test
    fun `CalibrationProfileDataStore isUniformScale rejects legacy distorted profiles`() {
        val patch = ArgbImage(10, 10, IntArray(100))
        val uniformProfile = CalibrationProfile(
            screenWidth = 2720,
            screenHeight = 1260,
            scaleX = 1.16667,
            scaleY = 1.16667,
            originX = -33.0,
            originY = 0.0,
            tapX = 1377.0,
            tapY = 433.0,
            toggleOnPatch = patch,
            source = CalibrationProfile.Source.AUTO,
            createdAt = 1000L
        )
        assertTrue(CalibrationProfileDataStore.isUniformScale(uniformProfile))

        val distortedProfile = uniformProfile.copy(
            scaleX = 1.10305,
            scaleY = 1.15269,
            source = CalibrationProfile.Source.MANUAL
        )
        assertFalse(CalibrationProfileDataStore.isUniformScale(distortedProfile))

        // 等比但缩放偏离屏幕高度基准比例（1260 / 1080 = 1.1667）超过 4% 的坏档案（例如 scale = 1.101，偏差约 5.6%）应被自动淘汰
        val badSnappedProfile = uniformProfile.copy(
            scaleX = 1.101,
            scaleY = 1.101,
            source = CalibrationProfile.Source.MANUAL
        )
        assertFalse(CalibrationProfileDataStore.isUniformScale(badSnappedProfile))
    }
}
