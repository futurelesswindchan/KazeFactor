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
 * NOTE: 模板只支持横纵相同的缩放，取 [Calibration.scaleX]。
 * TODO(第 3c 步): 手动吸附得到的横纵不等的缩放，需要 `TemplateBank` 按两个方向分别缩放。
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

    /**
     * 最近一次生成的匹配器及其缩放。整体替换，不加锁：并发时最多重复生成一次，
     * 生成结果只取决于缩放，所以谁覆盖谁都一样。
     */
    @Volatile
    private var cached: Pair<Double, SlotMatcher>? = null

    /** 按 [calibration] 的缩放生成匹配器，组装成识别器。 */
    fun create(calibration: Calibration): FactorRecognizer =
        FactorRecognizer(matcherFor(calibration.scaleX), calibration)

    /** 按 [scale] 生成匹配器；与上一次缩放相同时直接复用。 */
    fun matcherFor(scale: Double): SlotMatcher {
        cached?.let { (s, m) -> if (s == scale) return m }
        val bank = TemplateBank(database, { icons.getValue(it.id) }, scale)
        val questions = questionImages.map { renderQuestionTemplate(it, bank.slotSize) }
        return SlotMatcher(bank, questions, thresholds).also { cached = scale to it }
    }

    companion object {
        /**
         * 问号原图（参考截图上裁出的 90×90 槽位）缩放到 [slotSize] 见方，作为该缩放下的问号模板。
         *
         * 问号原图整张不透明，缩放后掩膜就是整个槽位。
         */
        fun renderQuestionTemplate(raw: ArgbImage, slotSize: Int): MaskedTemplate =
            MaskedTemplate(ImageOps.resizeAreaAverage(raw, slotSize, slotSize))
    }
}
