package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps

/**
 * 按校准结果生成 [FactorRecognizer]：模板库、问号模板都按当前缩放重新生成。
 *
 * 图标原图和问号原图在构造时一次读入并持有，之后每次 [create] 只做缩放，不再读文件。
 * 最近一次生成的模板库按缩放缓存，同一个 [Calibration] 先做槽位验证、再正式识别时不会重复生成。
 *
 * ```
 * val factory = RecognizerFactory(database, iconLoader, questionImages)
 * val calibration = calibrator.calibrate(frame) ?: return
 * val recognizer = factory.create(calibration)
 * ```
 *
 * 横纵缩放可以不同（手动吸附的结果），模板库和问号模板都按 [Calibration.scaleX]、[Calibration.scaleY] 分别缩放。
 *
 * @param database 因子数据库
 * @param iconLoader 按因子读取图标原图（`assets/factors/icons/`），构造时对每个因子调用一次
 * @param questionImages 第 1~4 槽的问号原图（`assets/recognition/question_slot1~4.png`），各为 90×90
 * @param thresholds 匹配阈值
 * @throws IllegalArgumentException 问号原图数量或尺寸不符时
 */
class RecognizerFactory(
    private val database: FactorDatabase,
    iconLoader: (Factor) -> ArgbImage,
    private val questionImages: List<ArgbImage>,
    private val thresholds: MatchThresholds = MatchThresholds()
) {
    init {
        require(questionImages.size == ReferenceLayout.SLOT_COUNT) {
            "需要 ${ReferenceLayout.SLOT_COUNT} 张问号原图，实际 ${questionImages.size} 张"
        }
        questionImages.forEachIndexed { i, img ->
            require(img.width == ReferenceLayout.SLOT_SIZE && img.height == ReferenceLayout.SLOT_SIZE) {
                "第 ${i + 1} 槽问号原图应为 ${ReferenceLayout.SLOT_SIZE} 见方，实际 ${img.width}x${img.height}"
            }
        }
    }

    private val icons: Map<Int, ArgbImage> = database.factors.associate { it.id to iconLoader(it) }

    /** 最近一次生成的匹配器及其横纵缩放。 */
    private class CachedMatcher(val scaleX: Double, val scaleY: Double, val matcher: SlotMatcher)

    /**
     * 最近一次生成的匹配器。整体替换，不加锁：并发时最多重复生成一次，
     * 生成结果只取决于缩放，所以谁覆盖谁都一样。
     */
    @Volatile
    private var cached: CachedMatcher? = null

    /** 按 [calibration] 的横纵缩放生成匹配器，组装成识别器。 */
    fun create(calibration: Calibration): FactorRecognizer =
        FactorRecognizer(matcherFor(calibration.scaleX, calibration.scaleY), calibration)

    /**
     * 按 ([scaleX], [scaleY]) 生成匹配器；与上一次缩放相同时直接复用。
     *
     * @param scaleY 纵向缩放，默认与 [scaleX] 相同
     */
    fun matcherFor(scaleX: Double, scaleY: Double = scaleX): SlotMatcher {
        cached?.let { if (it.scaleX == scaleX && it.scaleY == scaleY) return it.matcher }
        val bank = TemplateBank(database, { icons.getValue(it.id) }, scaleX, scaleY)
        val questions = questionImages.map { renderQuestionTemplate(it, bank.slotWidth, bank.slotHeight) }
        return SlotMatcher(bank, questions, thresholds).also { cached = CachedMatcher(scaleX, scaleY, it) }
    }

    companion object {
        /**
         * 问号原图（参考截图上裁出的 90×90 槽位）缩放到 `slotWidth × slotHeight`，作为该缩放下的问号模板。
         *
         * 问号原图整张不透明，缩放后掩膜就是整个槽位。
         */
        fun renderQuestionTemplate(raw: ArgbImage, slotWidth: Int, slotHeight: Int): MaskedTemplate =
            MaskedTemplate(ImageOps.resizeAreaAverage(raw, slotWidth, slotHeight))
    }
}
