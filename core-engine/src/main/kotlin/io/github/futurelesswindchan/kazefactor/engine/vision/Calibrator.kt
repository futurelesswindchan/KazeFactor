package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.image.rgbAbsDiffSum
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 帮助图标粗定位的一个候选（架构方案第 3.1 节第 1 段）。
 *
 * 同一屏上有 3 个外观相同的帮助图标，粗定位返回按距离排序的多个候选，
 * 正确的那个由第 2 段的面板边框精修和槽位验证筛选。
 *
 * NOTE: 图标只有 33 px 宽，[scale] 的误差可能有好几个百分点（图标宽 < 25 px 时最多约 6%），
 * 只能用来缩小面板边框精修的搜索范围，不能直接拿来换算槽位。
 *
 * @property scale 粗略缩放 `s0`
 * @property x 帮助图标左上角在屏幕上的横坐标（像素下标，等于左边界）
 * @property y 帮助图标左上角在屏幕上的纵坐标
 * @property distance 最优位置处的 RGB 平均绝对差（0~255），越小越像
 */
data class HelpIconEstimate(val scale: Double, val x: Int, val y: Int, val distance: Double) {

    /** 由图标位置推出的粗略横向偏移 `ox0 = x − s0 × 1475`。 */
    val originX: Double get() = x - scale * ReferenceLayout.HELP_ICON_RECT.x

    /** 由图标位置推出的粗略纵向偏移 `oy0 = y − s0 × 125`。 */
    val originY: Double get() = y - scale * ReferenceLayout.HELP_ICON_RECT.y
}

/**
 * 自动校准：帮助图标粗定位 + 面板边框精修，得到 [Calibration]（架构方案第 3.1 节）。
 *
 * 目前只实现了第 1 段 [locateHelpIcon]。
 * TODO(第 3a 步): 第 2 段面板边框精修、残差检查与槽位验证。
 *
 * ```
 * val calibrator = Calibrator(helpIconTemplate)
 * val candidates = calibrator.locateHelpIcon(screenshot)
 * // 按距离升序依次交给第 2 段，取第一个通过残差检查和槽位验证的结果
 * ```
 *
 * NOTE: 本类不可变，可在多个协程间共享。
 *
 * @param helpIconTemplate 帮助图标模板 `assets/recognition/help.png`，尺寸须与 [ReferenceLayout.HELP_ICON_RECT] 一致
 * @throws IllegalArgumentException 模板尺寸不符时
 */
class Calibrator(private val helpIconTemplate: ArgbImage) {

    init {
        val r = ReferenceLayout.HELP_ICON_RECT
        require(helpIconTemplate.width == r.width && helpIconTemplate.height == r.height) {
            "帮助图标模板应为 ${r.width}x${r.height}，实际 ${helpIconTemplate.width}x${helpIconTemplate.height}"
        }
    }

    /**
     * 在整帧画面里找帮助图标，返回按距离升序的多个候选。
     *
     * 做法：
     * 1. 初始猜测 `scale = 屏幕高度 / 1080`，在猜测值的 0.80~1.20 倍内按 0.02 的步长取 21 个候选缩放。
     * 2. 画面和模板都缩到 1/4，全屏搜索，保留距离最小、互不重叠的前 [MAX_CANDIDATES] 个位置。
     * 3. 回到原分辨率，每个位置都在 ±[FINE_RADIUS] px 内对全部候选缩放精修。
     *
     * 同屏有 3 个外观相同的图标，1/4 分辨率下它们的距离只差约 0.5，只保留 1 个位置时很容易选错
     * （架构方案第 3.1 节第 1 段第 4、5 条）。
     *
     * 取整后尺寸相同的几个候选缩放，搜索结果完全一样，只算一次，返回它们的平均值作为 `s0`，
     * 避免总是偏向这一组里最小的那个。
     *
     * NOTE: 本函数不判断「是不是因子界面」，只要画面放得下帮助图标就会返回候选。
     *
     * @param frame 完整截图
     * @return 按 [HelpIconEstimate.distance] 升序的候选，最多 [MAX_CANDIDATES] 个；画面小到放不下图标时为空
     */
    fun locateHelpIcon(frame: ArgbImage): List<HelpIconEstimate> {
        val guess = frame.height.toDouble() / ReferenceLayout.HEIGHT
        val scales = DoubleArray(SCALE_COUNT) { guess * (MIN_SCALE_RATIO + it * SCALE_STEP) }
        return coarseSearch(frame, scales)
            .mapNotNull { (x, y) -> fineSearch(frame, scales, x, y) }
            .sortedBy { it.distance }
    }

    /**
     * 1/4 分辨率全屏搜索，返回距离最小、互不重叠的前 [MAX_CANDIDATES] 个位置（已换算回原分辨率）。
     *
     * 每个位置只记录所有尺寸里的最小距离和对应的模板尺寸，内存只占两个和缩小后画面一样大的数组。
     * 选出一个位置后，把它周围一个模板大小内的位置都排除，避免同一个图标被选好几次
     * （即非极大值抑制）。
     */
    private fun coarseSearch(frame: ArgbImage, scales: DoubleArray): List<Pair<Int, Int>> {
        val smallWidth = frame.width / COARSE_FACTOR
        val smallHeight = frame.height / COARSE_FACTOR
        if (smallWidth < 1 || smallHeight < 1) return emptyList()
        val small = ImageOps.resizeAreaAverage(frame, smallWidth, smallHeight)
        // 宽高不是 4 的倍数时，实际缩小比例略大于 4，两个方向分开算
        val ratioX = frame.width.toDouble() / smallWidth
        val ratioY = frame.height.toDouble() / smallHeight

        val sizes = scales
            .map { s -> templateSize(s / ratioX, s / ratioY) }
            .distinct()
            .filter { (w, h) -> w <= smallWidth && h <= smallHeight }
        if (sizes.isEmpty()) return emptyList()

        val bestDistance = DoubleArray(smallWidth * smallHeight) { Double.MAX_VALUE }
        val bestSize = IntArray(smallWidth * smallHeight) { -1 }
        sizes.forEachIndexed { sizeIndex, (w, h) ->
            val template = ImageOps.resizeAreaAverage(helpIconTemplate, w, h)
            val denominator = (w * h * 3).toDouble()
            for (y in 0..smallHeight - h) {
                for (x in 0..smallWidth - w) {
                    val i = y * smallWidth + x
                    // 这个位置已有别的尺寸的结果时，超过它就提前放弃；sum 是整数，ceil 后比较结果不变
                    val limit = if (bestSize[i] < 0) Long.MAX_VALUE else ceil(bestDistance[i] * denominator).toLong()
                    val d = sumAbsDiff(small, template, x, y, limit) / denominator
                    if (d < bestDistance[i]) {
                        bestDistance[i] = d
                        bestSize[i] = sizeIndex
                    }
                }
            }
        }

        val picked = mutableListOf<Pair<Int, Int>>()
        val suppressed = BooleanArray(bestDistance.size)
        repeat(MAX_CANDIDATES) {
            var minIndex = -1
            for (i in bestDistance.indices) {
                if (bestSize[i] < 0 || suppressed[i]) continue
                if (minIndex < 0 || bestDistance[i] < bestDistance[minIndex]) minIndex = i
            }
            if (minIndex < 0) return@repeat
            val px = minIndex % smallWidth
            val py = minIndex / smallWidth
            val (w, h) = sizes[bestSize[minIndex]]
            for (y in maxOf(0, py - h + 1)..minOf(smallHeight - 1, py + h - 1)) {
                for (x in maxOf(0, px - w + 1)..minOf(smallWidth - 1, px + w - 1)) {
                    suppressed[y * smallWidth + x] = true
                }
            }
            picked += (px * ratioX).roundToInt() to (py * ratioY).roundToInt()
        }
        return picked
    }

    /** 原分辨率下，在 ([centerX], [centerY]) ±[FINE_RADIUS] 内对每种模板尺寸精修，返回最优的一个。 */
    private fun fineSearch(frame: ArgbImage, scales: DoubleArray, centerX: Int, centerY: Int): HelpIconEstimate? {
        var best: HelpIconEstimate? = null
        for ((size, groupScales) in scales.groupBy { templateSize(it, it) }) {
            val (w, h) = size
            if (w > frame.width || h > frame.height) continue
            val template = ImageOps.resizeAreaAverage(helpIconTemplate, w, h)
            val denominator = (w * h * 3).toDouble()
            // 换算成整数上限，用于提前放弃；sum 是整数，ceil 后「sum < limit」与「距离 < 当前最优」等价
            var limit = best?.let { ceil(it.distance * denominator).toLong() } ?: Long.MAX_VALUE
            var bestX = -1
            var bestY = -1
            for (y in (centerY - FINE_RADIUS).coerceIn(0, frame.height - h)..(centerY + FINE_RADIUS).coerceIn(0, frame.height - h)) {
                for (x in (centerX - FINE_RADIUS).coerceIn(0, frame.width - w)..(centerX + FINE_RADIUS).coerceIn(0, frame.width - w)) {
                    val sum = sumAbsDiff(frame, template, x, y, limit)
                    if (sum < limit) {
                        limit = sum
                        bestX = x
                        bestY = y
                    }
                }
            }
            if (bestX >= 0) best = HelpIconEstimate(groupScales.average(), bestX, bestY, limit / denominator)
        }
        return best
    }

    companion object {
        /** 候选缩放的下限，相对初始猜测。 */
        const val MIN_SCALE_RATIO = 0.80

        /** 候选缩放的步长，相对初始猜测。 */
        const val SCALE_STEP = 0.02

        /** 候选缩放个数：0.80, 0.82, …, 1.20。 */
        const val SCALE_COUNT = 21

        /** 粗搜时画面缩小的倍数。 */
        const val COARSE_FACTOR = 4

        /** 精修时在粗搜位置周围搜索的半径（像素）。 */
        const val FINE_RADIUS = 4

        /** 粗搜保留的候选数。同屏已知 3 个帮助图标，留 2 个余量给噪声造成的假匹配。 */
        const val MAX_CANDIDATES = 5

        /** 帮助图标在缩放 ([sx], [sy]) 下的像素尺寸，最小为 1。 */
        private fun templateSize(sx: Double, sy: Double): Pair<Int, Int> {
            val r = ReferenceLayout.HELP_ICON_RECT
            return maxOf(1, (r.width * sx).roundToInt()) to maxOf(1, (r.height * sy).roundToInt())
        }

        /**
         * 模板左上角放在 ([left], [top]) 时 RGB 三通道绝对差之和。
         *
         * 逐行累加，一旦达到 [limit] 就提前返回（此时返回值 ≥ [limit]，只表示「不比当前最优好」）。
         */
        private fun sumAbsDiff(image: ArgbImage, template: ArgbImage, left: Int, top: Int, limit: Long): Long {
            val tw = template.width
            var sum = 0L
            var row = 0
            while (row < template.height && sum < limit) {
                val srcBase = (top + row) * image.width + left
                val tplBase = row * tw
                for (col in 0 until tw) sum += rgbAbsDiffSum(image.pixels[srcBase + col], template.pixels[tplBase + col])
                row++
            }
            return sum
        }
    }
}
