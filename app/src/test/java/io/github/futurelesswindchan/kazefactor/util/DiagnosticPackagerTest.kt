package io.github.futurelesswindchan.kazefactor.util

import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.loop.PauseReason
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibration
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfile
import io.github.futurelesswindchan.kazefactor.engine.vision.CandidateScore
import io.github.futurelesswindchan.kazefactor.engine.vision.ScreenState
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotAnalysis
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiagnosticPackager] 元数据与排行榜 JSON 生成单元测试。
 */
class DiagnosticPackagerTest {

    private val database = FactorDatabase(
        dataVersion = 1,
        gameVersion = "8.7.0",
        factors = listOf(
            Factor(
                id = 1,
                key = "double_joy",
                name = "双倍快乐",
                nameEn = "Double Joy",
                description = "",
                descriptionEn = "",
                category = FactorCategory.MAJOR_BAD,
                iconFileName = "double_joy.png"
            ),
            Factor(
                id = 51,
                key = "infinite_energy",
                name = "无限能量",
                nameEn = "Infinite Energy",
                description = "",
                descriptionEn = "",
                category = FactorCategory.MAJOR_GOOD,
                iconFileName = "infinite_energy.png"
            )
        ),
        conflicts = emptyList(),
        exclusiveGroups = emptyList()
    )

    @Test
    fun `buildDeviceInfoJson serializes device and recipe metadata`() {
        val recipe = Recipe(
            id = "r1",
            name = "无限能量爽局",
            wantedFactorIds = setOf(51),
            unwantedFactorIds = setOf(1)
        )
        val json = DiagnosticPackager.buildDeviceInfoJson(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "23049RAD8C",
            device = "marble",
            sdkInt = 34,
            androidRelease = "14",
            screenWidthPx = 2400,
            screenHeightPx = 1080,
            densityDpi = 440,
            density = 2.75f,
            exportedAtMs = 1700000000000L,
            pauseReason = PauseReason.CONSECUTIVE_FAILURES,
            selectedRecipe = recipe,
            frameCount = 3
        )

        assertEquals("Xiaomi", json["manufacturer"]?.jsonPrimitive?.content)
        assertEquals("23049RAD8C", json["model"]?.jsonPrimitive?.content)
        assertEquals("34", json["sdkInt"]?.jsonPrimitive?.content)
        assertEquals("CONSECUTIVE_FAILURES", json["pauseReason"]?.jsonPrimitive?.content)
        assertEquals("3", json["capturedFrameCount"]?.jsonPrimitive?.content)
        assertEquals("无限能量爽局", json["selectedRecipe"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `buildCalibrationJson serializes profile fields accurately`() {
        val profile = CalibrationProfile(
            screenWidth = 2388,
            screenHeight = 1080,
            scaleX = 1.0,
            scaleY = 1.0,
            originX = 0.0,
            originY = 0.0,
            tapX = 1377.0,
            tapY = 433.0,
            toggleOnPatch = ArgbImage(10, 10, IntArray(100)),
            source = CalibrationProfile.Source.MANUAL,
            createdAt = 123456789L
        )
        val json = DiagnosticPackager.buildCalibrationJson(profile, null)

        assertEquals("true", json["hasSavedProfile"]?.jsonPrimitive?.content)
        assertEquals("2388", json["screenWidth"]?.jsonPrimitive?.content)
        assertEquals("MANUAL", json["source"]?.jsonPrimitive?.content)
        assertEquals("1377.0", json["tapScreenX"]?.jsonPrimitive?.content)
    }

    @Test
    fun `buildFrameAnalysisJson includes slot rankings and handles infinite toggleDistance`() {
        val slotAnalyses = listOf(
            SlotAnalysis(
                slotIndex = 1,
                result = SlotResult.Factor(1),
                questionDistance = 42.5,
                ranking = listOf(
                    CandidateScore(factorId = 1, distance = 8.25, offsetX = -1, offsetY = 1),
                    CandidateScore(factorId = 51, distance = 29.0, offsetX = 0, offsetY = 0)
                ),
                secondaryResolved = false,
                secondaryMargin = null
            )
        )

        val json = DiagnosticPackager.buildFrameAnalysisJson(
            frameIndex = 1,
            capturedAtMs = 999L,
            screenState = ScreenState.FACTOR_SCREEN_ON,
            toggleDistance = Double.POSITIVE_INFINITY,
            slotAnalyses = slotAnalyses,
            effectiveCalibration = Calibration.IDENTITY,
            factorDatabase = database
        )

        assertEquals("FACTOR_SCREEN_ON", json["screenState"]?.jsonPrimitive?.content)
        // POSITIVE_INFINITY 应转为 null 避免非标准 JSON
        assertEquals("null", json["toggleDistance"].toString())

        val slots = json["slots"]!!.jsonArray
        assertEquals(1, slots.size)
        val firstSlot = slots[0].jsonObject
        assertEquals("Factor", firstSlot["resultType"]?.jsonPrimitive?.content)
        assertTrue(firstSlot["resultSummary"]!!.jsonPrimitive.content.contains("双倍快乐"))

        val ranking = firstSlot["ranking"]!!.jsonArray
        assertEquals(2, ranking.size)
        assertEquals("double_joy", ranking[0].jsonObject["factorKey"]?.jsonPrimitive?.content)
        assertEquals("双倍快乐", ranking[0].jsonObject["factorName"]?.jsonPrimitive?.content)
    }
}
