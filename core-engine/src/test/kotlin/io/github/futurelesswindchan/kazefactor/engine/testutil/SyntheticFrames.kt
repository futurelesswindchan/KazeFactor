package io.github.futurelesswindchan.kazefactor.engine.testutil

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibration
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/**
 * 合成测试帧及其对应的真实坐标换算参数。
 *
 * @property image 合成后的测试画面
 * @property truth 参考坐标系到该测试画面的真实换算参数
 */
class SyntheticFrame(val image: ArgbImage, val truth: Calibration)

/**
 * 合成分辨率与跨机型矩阵测试图像生成工具：支持等比/非等比缩放、四周黑边填充、挖孔避让偏移模拟与 JPEG 有损重压缩。
 */
object SyntheticFrames {

    /**
     * 将参考截图按 [scale] 等比缩放，并居中绘制到带四周黑边的画布上。
     *
     * @param frame 原始参考截图
     * @param scale 等比缩放系数
     * @param padX 左右两侧各自填充的黑边宽度（单位：px）
     * @param padY 上下两侧各自填充的黑边高度（单位：px）
     */
    fun scaleWithBorder(frame: ArgbImage, scale: Double, padX: Int = 0, padY: Int = 0): SyntheticFrame =
        scaleWithBorderXY(frame, scale, scale, padX, padY)

    /**
     * 将参考截图按横纵独立缩放系数缩放，并绘制到带四周黑边的画布上。
     *
     * @param frame 原始参考截图
     * @param scaleX 横向缩放系数
     * @param scaleY 纵向缩放系数
     * @param padX 左右两侧各自填充的黑边宽度（单位：px）
     * @param padY 上下两侧各自填充的黑边高度（单位：px）
     */
    fun scaleWithBorderXY(frame: ArgbImage, scaleX: Double, scaleY: Double, padX: Int = 0, padY: Int = 0): SyntheticFrame {
        val w = (frame.width * scaleX).roundToInt()
        val h = (frame.height * scaleY).roundToInt()
        val resized = ImageOps.resizeAreaAverage(frame, w, h)
        val canvas = ArgbImage.filled(w + 2 * padX, h + 2 * padY, BLACK)
        val image = ImageOps.drawOver(canvas, resized, padX, padY)
        val truth = Calibration(
            scaleX = w.toDouble() / frame.width,
            scaleY = h.toDouble() / frame.height,
            originX = padX.toDouble(),
            originY = padY.toDouble()
        )
        return SyntheticFrame(image, truth)
    }

    /**
     * 将基准参考截图按目标设备屏幕高度等比缩放，并合成到指定物理分辨率的黑色画布上。
     *
     * 模拟主流 Android 设备在横屏模式下的游戏画面渲染规律：纵向撑满 [targetHeight]，
     * 横向按 `targetHeight / 1080.0` 等比缩放后水平居中，并叠加 [notchOffsetLeft] 模拟左侧挖孔区域避让偏移。
     * 当缩放后的画面宽度大于 [targetWidth] 时，超出屏幕左右边界的边缘区域自动裁切；小于时两侧填充黑边。
     *
     * @param source 2388×1080 基准参考截图
     * @param targetWidth 目标设备横屏物理宽度（单位：px）
     * @param targetHeight 目标设备横屏物理高度（单位：px）
     * @param notchOffsetLeft 模拟挖孔避让产生的水平偏移量（单位：px）
     * @return 合成后的全屏图像与对应的真值 [Calibration]
     */
    fun synthesizeDeviceFrame(
        source: ArgbImage,
        targetWidth: Int,
        targetHeight: Int,
        notchOffsetLeft: Int = 0
    ): Pair<ArgbImage, Calibration> {
        val scale = targetHeight.toDouble() / ReferenceLayout.HEIGHT
        val scaledW = (ReferenceLayout.WIDTH * scale).roundToInt()
        val scaledGame = ImageOps.resizeAreaAverage(source, scaledW, targetHeight)

        val originX = (targetWidth - scaledW) / 2.0 + notchOffsetLeft
        val originY = 0.0

        val fullCanvas = ArgbImage(targetWidth, targetHeight, IntArray(targetWidth * targetHeight) { BLACK })
        val offsetXInt = originX.roundToInt()
        for (y in 0 until targetHeight) {
            val srcRow = y * scaledGame.width
            val dstRow = y * targetWidth + offsetXInt
            for (x in 0 until scaledW) {
                val dstX = offsetXInt + x
                if (dstX in 0 until targetWidth) {
                    fullCanvas.pixels[dstRow + x] = scaledGame.pixels[srcRow + x]
                }
            }
        }
        return fullCanvas to Calibration(scale, scale, originX, originY)
    }

    /** 按指定质量系数 [quality]（0~1）执行一次 JPEG 编码与解码，模拟有损截图。 */
    fun jpegRecompress(image: ArgbImage, quality: Float): ArgbImage {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        rgb.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bytes).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(rgb, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        val decoded = ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))
        return ArgbImage(
            decoded.width, decoded.height,
            decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width)
        )
    }

    private const val BLACK = 0xFF000000.toInt()
}
