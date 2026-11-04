package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import java.util.Base64

/**
 * [CalibrationProfile] 与文本的互转，供 app 存进 DataStore（`calibration_profile`，第 6 步）。
 *
 * 开关参照块按架构方案第 3.4.1 节「PNG 编码后转 Base64」保存。PNG 编解码依赖平台
 * （Android 用 `Bitmap.compress`，电脑上的测试用 `ImageIO`），所以由调用方传入，本类只管文本格式。
 *
 * 文本格式：每行一个 `键=值`，第一行是版本号。小数用 Kotlin 的 `Double.toString()`，
 * 读回时 `toDouble()` 得到完全相同的值，所以换算结果逐位不变。
 *
 * ```
 * val codec = CalibrationProfileCodec(encodePng = ::bitmapToPng, decodePng = ::pngToArgbImage)
 * val text = codec.encode(profile)
 * val restored = codec.decode(text) ?: return // 损坏或版本不认识时当作没有档案
 * ```
 *
 * NOTE: 键名和格式在第一次发布后就不能随便改，改格式时提升 [VERSION] 并保留旧版本的读取。
 *
 * @param encodePng 把图像编码成 PNG 字节
 * @param decodePng 把 PNG 字节解码成图像，失败时应抛异常
 */
class CalibrationProfileCodec(
    private val encodePng: (ArgbImage) -> ByteArray,
    private val decodePng: (ByteArray) -> ArgbImage
) {

    /** 把档案编码成文本。 */
    fun encode(profile: CalibrationProfile): String = buildString {
        appendLine("$KEY_VERSION=$VERSION")
        appendLine("$KEY_SCREEN_WIDTH=${profile.screenWidth}")
        appendLine("$KEY_SCREEN_HEIGHT=${profile.screenHeight}")
        appendLine("$KEY_SCALE_X=${profile.scaleX}")
        appendLine("$KEY_SCALE_Y=${profile.scaleY}")
        appendLine("$KEY_ORIGIN_X=${profile.originX}")
        appendLine("$KEY_ORIGIN_Y=${profile.originY}")
        appendLine("$KEY_TAP_X=${profile.tapX}")
        appendLine("$KEY_TAP_Y=${profile.tapY}")
        appendLine("$KEY_SOURCE=${profile.source.name}")
        appendLine("$KEY_CREATED_AT=${profile.createdAt}")
        appendLine("$KEY_TOGGLE_PATCH=${Base64.getEncoder().encodeToString(encodePng(profile.toggleOnPatch))}")
    }

    /**
     * 从文本还原档案。
     *
     * @return 缺字段、数值格式错误、版本不认识、PNG 解不开或字段值非法时返回 null，
     *   调用方按「没有档案」处理（走自动校准），不会崩溃
     */
    fun decode(text: String): CalibrationProfile? {
        val fields = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()
        if (fields[KEY_VERSION]?.toIntOrNull() != VERSION) return null
        return try {
            CalibrationProfile(
                screenWidth = fields.getValue(KEY_SCREEN_WIDTH).toInt(),
                screenHeight = fields.getValue(KEY_SCREEN_HEIGHT).toInt(),
                scaleX = fields.getValue(KEY_SCALE_X).toDouble(),
                scaleY = fields.getValue(KEY_SCALE_Y).toDouble(),
                originX = fields.getValue(KEY_ORIGIN_X).toDouble(),
                originY = fields.getValue(KEY_ORIGIN_Y).toDouble(),
                tapX = fields.getValue(KEY_TAP_X).toDouble(),
                tapY = fields.getValue(KEY_TAP_Y).toDouble(),
                toggleOnPatch = decodePng(Base64.getDecoder().decode(fields.getValue(KEY_TOGGLE_PATCH))),
                source = CalibrationProfile.Source.valueOf(fields.getValue(KEY_SOURCE)),
                createdAt = fields.getValue(KEY_CREATED_AT).toLong()
            )
        } catch (e: Exception) {
            // 缺字段、数字或 Base64 格式错、枚举名不认识、CalibrationProfile 校验不过，
            // 以及平台实现的 decodePng 抛出的任何异常：档案坏了一律当作没有档案
            null
        }
    }

    companion object {
        /** 文本格式版本。 */
        const val VERSION = 1

        private const val KEY_VERSION = "version"
        private const val KEY_SCREEN_WIDTH = "screenWidth"
        private const val KEY_SCREEN_HEIGHT = "screenHeight"
        private const val KEY_SCALE_X = "scaleX"
        private const val KEY_SCALE_Y = "scaleY"
        private const val KEY_ORIGIN_X = "originX"
        private const val KEY_ORIGIN_Y = "originY"
        private const val KEY_TAP_X = "tapX"
        private const val KEY_TAP_Y = "tapY"
        private const val KEY_SOURCE = "source"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_TOGGLE_PATCH = "toggleOnPatch"
    }
}
