package io.github.futurelesswindchan.kazefactor.engine.testutil

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.image.ImageOps
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibration
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/**
 * 一张合成画面，以及生成它时用的真实换算（校准结果要和它比）。
 *
 * @property image 合成后的画面
 * @property truth 参考坐标到这张画面的真实换算；横纵缩放按取整后的实际尺寸计算，可能有极小差异
 */
class SyntheticFrame(val image: ArgbImage, val truth: Calibration)

/**
 * 合成分辨率测试用的画面变换：缩放、加黑边、JPEG 重压缩（架构方案第 9 节第 3 步验收 ①）。
 */
object SyntheticFrames {

    /**
     * 把参考截图按 [scale] 等比缩放，再贴到四周有黑边的画布上。
     *
     * @param padX 左右黑边宽度（两侧相同）
     * @param padY 上下黑边高度（两侧相同）
     */
    fun scaleWithBorder(frame: ArgbImage, scale: Double, padX: Int = 0, padY: Int = 0): SyntheticFrame =
        scaleWithBorderXY(frame, scale, scale, padX, padY)

    /**
     * 同 [scaleWithBorder]，但横纵分开缩放，模拟面板被横向拉伸（架构方案第 3.4 节，调研报告实验 11）。
     *
     * @param scaleX 横向缩放
     * @param scaleY 纵向缩放
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

    /** 按 [quality]（0~1）做一次 JPEG 编码再解码，模拟有损截图。 */
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
