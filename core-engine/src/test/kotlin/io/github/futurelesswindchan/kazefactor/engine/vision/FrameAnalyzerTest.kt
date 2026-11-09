package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [FrameAnalyzer] 与 [DetailedFrameAnalysis] 单元测试。
 */
class FrameAnalyzerTest {

    private val recognizerFactory = TestFixtures.recognizerFactory
    private val analyzer = FrameAnalyzer(recognizerFactory)

    private fun pngCodec() = CalibrationProfileCodec(
        encodePng = { img ->
            val buffered = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            buffered.setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width)
            ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
        },
        decodePng = { bytes ->
            val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("PNG 解码失败")
            ArgbImage(decoded.width, decoded.height, decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width))
        }
    )

    @Test
    fun `analyzeDetailed on enable01 returns all slot analyses with rankings`() {
        val frame = TestFixtures.loadScreenshot("enable01.jpg")
        val detailed = analyzer.analyzeDetailed(frame, Calibration.IDENTITY)

        assertEquals(ScreenState.FACTOR_SCREEN_ON, detailed.analysis.screenState)
        assertEquals(4, detailed.analysis.slotResults.size)
        assertEquals(4, detailed.slotAnalyses.size)

        // 验证每个槽位都有分析结果与候选排行榜
        for ((slotIndex, _, _, ranking) in detailed.slotAnalyses) {
            assertTrue(slotIndex in 1..4)
            assertTrue(ranking.isNotEmpty(), "每个槽位都应有非空的候选打分排行榜")
        }
    }

    @Test
    fun `analyzeDetailedWithProfile returns toggleDistance and slot analyses`() {
        val frame = TestFixtures.loadScreenshot("enable01.jpg")
        val patch = ProfileVerifier.captureTogglePatch(frame, Calibration.IDENTITY)
        assertNotNull(patch)

        val profile = CalibrationProfile.create(
            screenWidth = frame.width,
            screenHeight = frame.height,
            calibration = Calibration.IDENTITY,
            toggleOnPatch = patch,
            source = CalibrationProfile.Source.AUTO,
            createdAt = 123456L
        )

        val detailed = analyzer.analyzeDetailedWithProfile(frame, profile)
        assertEquals(ScreenState.FACTOR_SCREEN_ON, detailed.analysis.screenState)
        assertEquals(4, detailed.slotAnalyses.size)
        assertNotNull(detailed.toggleDistance)
        assertTrue(detailed.toggleDistance <= 15.0, "同图自核验开关距离应很小")
    }

    @Test
    fun `analyzeDetailed outside screen returns NOT_FACTOR_SCREEN`() {
        // 创建一个极小的画面，槽位搜索区域必然越界
        val smallFrame = ArgbImage(10, 10, IntArray(100))
        val detailed = analyzer.analyzeDetailed(smallFrame, Calibration.IDENTITY)

        assertEquals(ScreenState.NOT_FACTOR_SCREEN, detailed.analysis.screenState)
        assertTrue(detailed.slotAnalyses.isEmpty())
    }
}
