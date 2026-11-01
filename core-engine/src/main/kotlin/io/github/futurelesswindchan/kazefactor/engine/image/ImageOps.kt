package io.github.futurelesswindchan.kazefactor.engine.image

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 识别层用到的纯 Kotlin 图像操作：裁剪、面积平均缩放、贴图与合成。
 *
 * 全部用整数像素数组实现，不依赖 Android 或 AWT，保证电脑上的测试与手机上生成的模板逐像素一致。
 */
object ImageOps {

    /**
     * 裁出 `(x, y)` 起、大小为 `width × height` 的矩形区域。
     *
     * @return 新图像，与 [src] 不共享像素数组
     * @throws IllegalArgumentException 区域尺寸非正或超出 [src] 边界时
     */
    fun crop(src: ArgbImage, x: Int, y: Int, width: Int, height: Int): ArgbImage {
        require(width > 0 && height > 0) { "裁剪尺寸必须为正数：${width}x$height" }
        require(x >= 0 && y >= 0 && x + width <= src.width && y + height <= src.height) {
            "裁剪区域 ($x, $y, ${width}x$height) 超出图像 ${src.width}x${src.height}"
        }
        val out = IntArray(width * height)
        for (row in 0 until height) {
            System.arraycopy(src.pixels, (y + row) * src.width + x, out, row * width, width)
        }
        return ArgbImage(width, height, out)
    }

    /**
     * 用面积平均（box filter）把图像缩放到 `width × height`，放大缩小都适用。
     *
     * 每个目标像素对应源图上一块矩形，按覆盖面积加权平均。RGB 按 alpha 加权，
     * 否则透明像素里无意义的颜色（通常是黑色）会渗进图标边缘，形成一圈暗边。
     *
     * @return 新图像；尺寸不变时返回像素数组的副本
     * @throws IllegalArgumentException 目标尺寸非正时
     */
    fun resizeAreaAverage(src: ArgbImage, width: Int, height: Int): ArgbImage {
        require(width > 0 && height > 0) { "缩放尺寸必须为正数：${width}x$height" }
        if (width == src.width && height == src.height) {
            return ArgbImage(width, height, src.pixels.copyOf())
        }
        val xSpans = axisSpans(src.width, width)
        val ySpans = axisSpans(src.height, height)
        val out = IntArray(width * height)

        for (dy in 0 until height) {
            val ys = ySpans[dy]
            for (dx in 0 until width) {
                val xs = xSpans[dx]
                var sumW = 0.0
                var sumA = 0.0
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                for (j in ys.weights.indices) {
                    val wy = ys.weights[j]
                    val rowBase = (ys.first + j) * src.width + xs.first
                    for (i in xs.weights.indices) {
                        val w = wy * xs.weights[i]
                        val p = src.pixels[rowBase + i]
                        val aw = alphaOf(p) * w
                        sumW += w
                        sumA += aw
                        sumR += redOf(p) * aw
                        sumG += greenOf(p) * aw
                        sumB += blueOf(p) * aw
                    }
                }
                out[dy * width + dx] = if (sumA <= 0.0) {
                    0 // 完全透明，颜色无意义，统一记为 0
                } else {
                    packArgb(
                        (sumA / sumW).roundToInt(),
                        (sumR / sumA).roundToInt(),
                        (sumG / sumA).roundToInt(),
                        (sumB / sumA).roundToInt()
                    )
                }
            }
        }
        return ArgbImage(width, height, out)
    }

    /**
     * 把 [src] 居中贴到 `canvasWidth × canvasHeight` 的画布上，直接覆盖不做混合。
     *
     * 尺寸差为奇数时，多出的 1 px 留在右侧和下侧。
     *
     * @param background 画布底色，默认全透明
     * @throws IllegalArgumentException [src] 比画布大时
     */
    fun placeCentered(src: ArgbImage, canvasWidth: Int, canvasHeight: Int, background: Int = 0): ArgbImage {
        require(src.width <= canvasWidth && src.height <= canvasHeight) {
            "图像 ${src.width}x${src.height} 放不进画布 ${canvasWidth}x$canvasHeight"
        }
        val out = IntArray(canvasWidth * canvasHeight) { background }
        val left = (canvasWidth - src.width) / 2
        val top = (canvasHeight - src.height) / 2
        for (row in 0 until src.height) {
            System.arraycopy(src.pixels, row * src.width, out, (top + row) * canvasWidth + left, src.width)
        }
        return ArgbImage(canvasWidth, canvasHeight, out)
    }

    /**
     * 按 alpha 把 [foreground] 以 source-over 方式合成到 [background] 的 `(left, top)` 处。
     *
     * 用于合成测试：把模板贴到纯色或截图背景上，模拟游戏里的显示效果。
     *
     * @return 新图像，[background] 本身不被修改
     * @throws IllegalArgumentException [foreground] 超出 [background] 边界时
     */
    fun drawOver(background: ArgbImage, foreground: ArgbImage, left: Int, top: Int): ArgbImage {
        require(
            left >= 0 && top >= 0 &&
                left + foreground.width <= background.width &&
                top + foreground.height <= background.height
        ) { "前景 ${foreground.width}x${foreground.height} 放在 ($left, $top) 超出背景边界" }

        val out = background.pixels.copyOf()
        for (y in 0 until foreground.height) {
            for (x in 0 until foreground.width) {
                val f = foreground.pixels[y * foreground.width + x]
                val fa = alphaOf(f)
                if (fa == 0) continue
                val index = (top + y) * background.width + left + x
                out[index] = if (fa == 255) f else blendOver(f, out[index])
            }
        }
        return ArgbImage(background.width, background.height, out)
    }

    /** source-over 混合单个像素。 */
    private fun blendOver(f: Int, b: Int): Int {
        val fa = alphaOf(f) / 255.0
        val ba = alphaOf(b) / 255.0
        val outA = fa + ba * (1 - fa)
        if (outA <= 0.0) return 0
        fun channel(fc: Int, bc: Int) = ((fc * fa + bc * ba * (1 - fa)) / outA).roundToInt()
        return packArgb(
            (outA * 255).roundToInt(),
            channel(redOf(f), redOf(b)),
            channel(greenOf(f), greenOf(b)),
            channel(blueOf(f), blueOf(b))
        )
    }

    /** 一个目标像素在某一轴上覆盖的源像素：从 [first] 开始，各自的覆盖长度为 [weights]。 */
    private class AxisSpan(val first: Int, val weights: DoubleArray)

    /** 预先算好每个目标坐标在源图该轴上的覆盖区间，避免在二重循环里重复计算。 */
    private fun axisSpans(srcLen: Int, dstLen: Int): Array<AxisSpan> {
        val ratio = srcLen.toDouble() / dstLen
        return Array(dstLen) { d ->
            val lo = d * ratio
            val hi = min((d + 1) * ratio, srcLen.toDouble())
            val first = floor(lo).toInt()
            val last = min(ceil(hi).toInt(), srcLen) - 1
            val weights = DoubleArray(last - first + 1) { k ->
                val s = first + k
                min(hi, s + 1.0) - max(lo, s.toDouble())
            }
            AxisSpan(first, weights)
        }
    }
}

/** 把 4 个 0~255 的通道值打包成 `0xAARRGGBB`，越界值会被截断到 0~255。 */
internal fun packArgb(a: Int, r: Int, g: Int, b: Int): Int =
    (a.coerceIn(0, 255) shl 24) or
        (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or
        b.coerceIn(0, 255)
