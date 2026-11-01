package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.image.alphaOf
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 只比较不透明像素的模板。
 *
 * 构造时把 alpha ≥ [alphaThreshold] 的像素坐标和颜色摊平成数组，
 * 匹配时只遍历这些像素，省掉逐像素判断透明度。
 *
 * @property image 模板原图，带透明通道
 * @param alphaThreshold 视为「不透明」的最低 alpha
 * @throws IllegalArgumentException 没有任何像素达到 [alphaThreshold] 时
 */
class MaskedTemplate(val image: ArgbImage, alphaThreshold: Int = TemplateBank.MASK_ALPHA) {

    /** 掩膜内像素的横坐标。 */
    internal val maskX: IntArray

    /** 掩膜内像素的纵坐标。 */
    internal val maskY: IntArray

    /** 掩膜内像素的颜色（`0xAARRGGBB`）。 */
    internal val maskColor: IntArray

    /** 掩膜内像素个数。 */
    val maskSize: Int

    init {
        val indices = image.pixels.indices.filter { alphaOf(image.pixels[it]) >= alphaThreshold }
        require(indices.isNotEmpty()) { "模板没有 alpha ≥ $alphaThreshold 的像素" }
        maskSize = indices.size
        maskX = IntArray(maskSize) { indices[it] % image.width }
        maskY = IntArray(maskSize) { indices[it] / image.width }
        maskColor = IntArray(maskSize) { image.pixels[indices[it]] }
    }

    private val opaque = BooleanArray(image.pixels.size).also { flags ->
        for (i in 0 until maskSize) flags[maskY[i] * image.width + maskX[i]] = true
    }

    /** (x, y) 是否在掩膜内。 */
    fun isOpaque(x: Int, y: Int): Boolean = opaque[y * image.width + x]
}

/**
 * 单个因子的匹配模板。
 *
 * @property factor 对应的因子
 * @property sizeFactor 生成模板时用的尺寸系数
 * @property template 已缩放并居中到槽位画布上的掩膜模板
 */
class FactorTemplate(val factor: Factor, val sizeFactor: Double, val template: MaskedTemplate) {
    override fun toString() = "FactorTemplate(${factor.key})"
}

/**
 * 由图标原图生成各槽位的候选模板，生成一次后缓存在内存里。
 *
 * 模板做法（架构方案第 3.2 节）：图标按「长边 = 90 × [scale] × 尺寸系数」用面积平均缩放，
 * 居中贴到槽位大小的透明画布上，alpha ≥ [MASK_ALPHA] 的像素作为掩膜。
 * 所有因子的画布同样大小、同样居中，所以不同模板的像素坐标可以直接对应，二次比较依赖这一点。
 *
 * ```
 * val bank = TemplateBank(database, iconLoader = { factor -> loadPng(factor.getIconFile()) })
 * val slot3 = bank.candidatesForSlot(3) // 39 个候选
 * ```
 *
 * @param database 因子数据库，决定生成哪些模板以及各槽位的候选
 * @param iconLoader 按因子读取图标原图（`assets/factors/icons/` 中的 PNG），读不到时应抛异常
 * @param scale 当前屏幕相对参考截图的缩放系数，未校准时为 1.0
 * @param sizeFactors 按因子 key 覆盖的尺寸系数，未列出的因子为 1.0
 * @throws IllegalArgumentException [scale] 非正，或某个系数使图标超出槽位时
 */
class TemplateBank(
    database: FactorDatabase,
    iconLoader: (Factor) -> ArgbImage,
    val scale: Double = 1.0,
    sizeFactors: Map<String, Double> = DEFAULT_SIZE_FACTORS
) {

    /** 当前缩放下的槽位边长，也是所有模板画布的边长。 */
    val slotSize: Int

    /** 全部因子的模板，按因子 id 索引。 */
    val templates: Map<Int, FactorTemplate>

    private val slotCandidates: List<List<FactorTemplate>>

    init {
        require(scale > 0) { "scale 必须为正数：$scale" }
        slotSize = max(1, (ReferenceLayout.SLOT_SIZE * scale).roundToInt())
        templates = database.factors.associate { factor ->
            val sizeFactor = sizeFactors[factor.key] ?: 1.0
            val longEdge = (ReferenceLayout.ICON_LONG_EDGE * scale * sizeFactor).roundToInt()
            require(longEdge in 1..slotSize) {
                "${factor.key} 的模板长边 $longEdge 超出槽位 $slotSize（尺寸系数 $sizeFactor）"
            }
            val image = renderTemplate(iconLoader(factor), longEdge, slotSize)
            factor.id to FactorTemplate(factor, sizeFactor, MaskedTemplate(image))
        }
        slotCandidates = (1..ReferenceLayout.SLOT_COUNT).map { slot ->
            templates.values.filter { it.factor.canAppearInSlot(slot) }.sortedBy { it.factor.id }
        }
    }

    /**
     * 返回第 [slotIndex] 槽可能出现的因子模板（按 `Factor.canAppearInSlot()` 剪枝）。
     *
     * 第 1~4 槽分别为 28、37、39、16 个。
     *
     * @param slotIndex 槽位序号，1~4
     */
    fun candidatesForSlot(slotIndex: Int): List<FactorTemplate> {
        require(slotIndex in 1..ReferenceLayout.SLOT_COUNT) { "槽位序号必须在 1~4 之间：$slotIndex" }
        return slotCandidates[slotIndex - 1]
    }

    companion object {
        /** 视为图标本体的最低 alpha，低于它的半透明边缘不参与比较。 */
        const val MASK_ALPHA = 200

        /**
         * 在游戏中显示得比 90 px 小的图标（第 0 节实测）。
         *
         * NOTE: 截图里从未出现过的 9 个因子暂按 1.0 处理，属于未验证，真机遇到时补测。
         */
        val DEFAULT_SIZE_FACTORS: Map<String, Double> = mapOf(
            "reforge_weapon" to 0.84,
            "good_luck" to 0.98,
            "exceptionally_talented" to 0.98
        )

        /** 等比缩放到长边 [longEdge]，再居中贴到 [canvasSize] 见方的透明画布上。 */
        internal fun renderTemplate(icon: ArgbImage, longEdge: Int, canvasSize: Int): ArgbImage {
            val ratio = longEdge.toDouble() / max(icon.width, icon.height)
            val width = (icon.width * ratio).roundToInt().coerceIn(1, canvasSize)
            val height = (icon.height * ratio).roundToInt().coerceIn(1, canvasSize)
            val resized = ImageOps.resizeAreaAverage(icon, width, height)
            return ImageOps.placeCentered(resized, canvasSize, canvasSize)
        }
    }
}
