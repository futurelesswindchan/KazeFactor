package io.github.futurelesswindchan.kazefactor.engine.image

import kotlin.math.abs

/**
 * 与平台无关的 ARGB 位图，识别层统一使用的图像表示。
 *
 * 像素按行优先存放，格式为 `0xAARRGGBB`，与 Android `Bitmap.getPixels()`
 * 和 Java `BufferedImage.getRGB()` 的输出一致，两端都能直接装入。
 * 识别代码只依赖本类而不依赖 Android `Bitmap`，所以电脑上的测试和手机上的运行逐像素一致。
 *
 * NOTE: 构造时直接持有传入的数组，不做复制。交出数组后调用方不应再修改它。
 *
 * @property width 宽度（像素），必须为正数
 * @property height 高度（像素），必须为正数
 * @property pixels 行优先的像素数组，长度必须等于 `width * height`
 * @throws IllegalArgumentException 尺寸非正或数组长度不符时
 */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "图像尺寸必须为正数：${width}x$height" }
        require(pixels.size == width * height) {
            "像素数 ${pixels.size} 与尺寸 ${width}x$height 不符"
        }
    }

    /** 读取 (x, y) 处的像素。 */
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    companion object {
        /** 创建一张所有像素都为 [argb] 的图像。 */
        fun filled(width: Int, height: Int, argb: Int): ArgbImage =
            ArgbImage(width, height, IntArray(width * height) { argb })
    }
}

/** 像素的 alpha 通道（0~255）。 */
internal fun alphaOf(argb: Int): Int = argb ushr 24

/** 像素的 R 通道（0~255）。 */
internal fun redOf(argb: Int): Int = (argb shr 16) and 0xFF

/** 像素的 G 通道（0~255）。 */
internal fun greenOf(argb: Int): Int = (argb shr 8) and 0xFF

/** 像素的 B 通道（0~255）。 */
internal fun blueOf(argb: Int): Int = argb and 0xFF

/** 两个像素 RGB 三通道绝对差之和（0~765），忽略 alpha。 */
internal fun rgbAbsDiffSum(p: Int, q: Int): Int =
    abs(redOf(p) - redOf(q)) + abs(greenOf(p) - greenOf(q)) + abs(blueOf(p) - blueOf(q))
