package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 从实机截图裁出识别素材，写入 `app/src/main/assets/recognition/`（架构方案第 8 节）。
 *
 * 这是一个工具，不是常规测试：平时自动跳过，只在需要重新裁取时手动运行：
 *
 * ```
 * .\gradlew.bat :core-engine:test --tests "*RecognitionAssetGenerator*" '-Pkaze.generateRecognitionAssets' --console=plain
 * ```
 *
 * NOTE: PowerShell 中 `-P` 参数必须加单引号。不加时 PowerShell 会在点号处把它拆成
 * `-Pkaze` 和 `.generateRecognitionAssets` 两个参数，Gradle 会把后者当成任务名而报错。
 *
 * 生成的文件：
 * - `dice.png`：骰子锚点，参考坐标 x 1475..1507、y 125..162（33×38）
 * - `question_slot1.png` ~ `question_slot4.png`：4 个槽位的问号模板，各 90×90
 *
 * 都从 [SOURCE] 裁取。7 张问号截图这两处的像素完全一致（`AnchorSurveyTest` 和架构方案第 0 节），
 * 任取一张结果相同。写完会读回比对，确认 PNG 编解码无损。
 */
class RecognitionAssetGenerator {

    @Test
    fun `从截图裁出骰子与问号模板`() {
        assumeTrue(
            "未带 -Pkaze.generateRecognitionAssets，跳过素材生成",
            System.getProperty("kaze.generateRecognitionAssets") == "true"
        )
        val outDir = File(
            System.getProperty("kaze.recognitionDir") ?: error("缺少系统属性 kaze.recognitionDir")
        ).apply { mkdirs() }
        val frame = TestFixtures.loadScreenshot(SOURCE)

        val outputs = buildList {
            val dice = ReferenceLayout.DICE_RECT
            add(DICE_FILE to ImageOps.crop(frame, dice.x, dice.y, dice.width, dice.height))
            for (slot in 1..ReferenceLayout.SLOT_COUNT) {
                val r = ReferenceLayout.slotRect(slot)
                add(questionFile(slot) to ImageOps.crop(frame, r.x, r.y, r.width, r.height))
            }
        }

        for ((name, image) in outputs) {
            val file = File(outDir, name)
            writePng(file, image)
            val readBack = ImageIO.read(file)
            assertEquals(image.width, readBack.width, "$name 宽度")
            assertEquals(image.height, readBack.height, "$name 高度")
            assertContentEquals(
                image.pixels,
                readBack.getRGB(0, 0, readBack.width, readBack.height, null, 0, readBack.width),
                "$name 读回后像素不一致"
            )
            println("已写入 ${file.absolutePath}（${image.width}x${image.height}）")
        }
    }

    private fun writePng(file: File, image: ArgbImage) {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        check(ImageIO.write(out, "png", file)) { "没有可用的 PNG 编码器" }
    }

    companion object {
        /** 素材来源截图。 */
        const val SOURCE = "disable01.jpg"

        /** 骰子模板文件名。 */
        const val DICE_FILE = "dice.png"

        /** 第 [slot] 槽问号模板的文件名。 */
        fun questionFile(slot: Int) = "question_slot$slot.png"
    }
}
