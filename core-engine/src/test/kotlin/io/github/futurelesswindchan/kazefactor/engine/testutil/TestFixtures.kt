package io.github.futurelesswindchan.kazefactor.engine.testutil

import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.vision.MaskedTemplate
import io.github.futurelesswindchan.kazefactor.engine.vision.MatchThresholds
import io.github.futurelesswindchan.kazefactor.engine.vision.RecognizerFactory
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotMatcher
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import io.github.futurelesswindchan.kazefactor.engine.vision.TemplateBank
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * 一张截图的答案：4 个槽位的期望结果。
 *
 * @property file 截图文件名，位于测试资源 `screenshots/` 下
 * @property expected 第 1~4 槽的期望结果，只会是 [SlotResult.Question] 或 [SlotResult.Factor]
 */
data class ScreenshotLabel(val file: String, val expected: List<SlotResult>)

/**
 * 识别测试共用的资源加载。
 *
 * - `factors.json` 与图标：通过 Gradle 传入的系统属性 `kaze.factorsJson`、`kaze.iconDir`
 *   直接读 app 的运行时资源，不再复制一份。
 * - 截图与 `labels.csv`：走测试 classpath（`src/test/resources/screenshots/`）。
 *
 * NOTE: 截图每张解码后约 10 MB，不做缓存，按需逐张读取，避免 47 张同时驻留内存。
 */
object TestFixtures {

    private val json = Json { ignoreUnknownKeys = true }

    val database: FactorDatabase by lazy {
        val text = File(requireProperty("kaze.factorsJson")).readText(Charsets.UTF_8)
        json.decodeFromString<FactorDatabase>(text)
    }

    private val iconDir: File by lazy { File(requireProperty("kaze.iconDir")) }

    /** scale = 1、默认尺寸系数的模板库（横纵相同）。 */
    val templateBank: TemplateBank by lazy { TemplateBank(database, ::loadIcon) }

    /**
     * 第 1~4 槽的问号原图（90×90），从 `assets/recognition/question_slot1~4.png` 读取。
     * 这些 PNG 由 `RecognitionAssetGenerator` 从 `disable01.jpg` 裁出，与现场裁取逐像素一致。
     */
    val questionImages: List<ArgbImage> by lazy {
        (1..ReferenceLayout.SLOT_COUNT).map { slot ->
            val file = File(recognitionDir, "question_slot$slot.png")
            require(file.isFile) { "找不到第 $slot 槽问号模板：${file.absolutePath}" }
            file.inputStream().use(::readImage)
        }
    }

    /**
     * 第 1~4 槽的问号掩膜模板（scale = 1，横纵相同），由 [questionImages] 直接包装。
     *
     * NOTE: 保留此属性是为了让已有的 `newMatcher()` 和不涉及校准的测试不需要改动。
     */
    val questionTemplates: List<MaskedTemplate> by lazy {
        questionImages.map { MaskedTemplate(it) }
    }

    private val recognitionDir: File by lazy { File(requireProperty("kaze.recognitionDir")) }

    /** 帮助图标模板 `assets/recognition/help.png`（33×38）。 */
    val helpIconTemplate: ArgbImage by lazy {
        val file = File(recognitionDir, "help.png")
        require(file.isFile) { "找不到帮助图标模板：${file.absolutePath}" }
        file.inputStream().use(::readImage)
    }

    /** `labels.csv` 的全部行，key 已换成因子 id；遇到未知 key 直接报错。 */
    val labels: List<ScreenshotLabel> by lazy {
        val text = resourceStream("screenshots/labels.csv").use { it.readBytes().toString(Charsets.UTF_8) }
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .drop(1) // 表头 file,slot1,slot2,slot3,slot4
            .map { line ->
                val cells = line.split(',').map { it.trim() }
                require(cells.size == 5) { "labels.csv 行格式错误：$line" }
                ScreenshotLabel(cells[0], cells.drop(1).map(::parseLabelCell))
            }
            .toList()
    }

    /** 可按任意缩放生成识别器的工厂。 */
    val recognizerFactory: RecognizerFactory by lazy {
        RecognizerFactory(database, ::loadIcon, questionImages)
    }

    /** 用 [thresholds] 创建一个新的匹配器（scale = 1）。 */
    fun newMatcher(thresholds: MatchThresholds = MatchThresholds()): SlotMatcher =
        SlotMatcher(templateBank, questionTemplates, thresholds)

    /** 读取测试资源 `screenshots/` 下的截图。 */
    fun loadScreenshot(fileName: String): ArgbImage =
        resourceStream("screenshots/$fileName").use(::readImage)

    /** 读取因子的图标原图。 */
    fun loadIcon(factor: Factor): ArgbImage {
        val file = File(iconDir, factor.getIconFile())
        require(file.isFile) { "找不到 ${factor.key} 的图标：${file.absolutePath}" }
        return file.inputStream().use(::readImage)
    }

    /** 把识别结果转成便于阅读的文字，因子显示为 key。 */
    fun describe(result: SlotResult): String = when (result) {
        SlotResult.Question -> "?"
        SlotResult.Unknown -> "Unknown"
        is SlotResult.Factor -> keyOf(result.factorId)
        is SlotResult.Ambiguous -> result.candidateIds.joinToString("|", "Ambiguous(", ")") { keyOf(it) }
    }

    /** 因子 id 转 key，未知 id 显示为 `#id`。 */
    fun keyOf(factorId: Int): String = database.getFactorById(factorId)?.key ?: "#$factorId"

    private fun parseLabelCell(cell: String): SlotResult =
        if (cell == "?") {
            SlotResult.Question
        } else {
            val factor = database.getFactorByKey(cell) ?: error("labels.csv 中有未知因子 key：$cell")
            SlotResult.Factor(factor.id)
        }

    private fun readImage(stream: InputStream): ArgbImage {
        val image = ImageIO.read(stream) ?: error("无法解码图片")
        val w = image.width
        val h = image.height
        // getRGB 统一输出非预乘的 0xAARRGGBB，与 Android Bitmap.getPixels() 一致
        return ArgbImage(w, h, image.getRGB(0, 0, w, h, null, 0, w))
    }

    private fun resourceStream(path: String): InputStream =
        Thread.currentThread().contextClassLoader.getResourceAsStream(path)
            ?: error("找不到测试资源：$path")

    private fun requireProperty(name: String): String =
        System.getProperty(name) ?: error("缺少系统属性 $name，请通过 Gradle 运行测试（见 core-engine/build.gradle.kts）")
}
