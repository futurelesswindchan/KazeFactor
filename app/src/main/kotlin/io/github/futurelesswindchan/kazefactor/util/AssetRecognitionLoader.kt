package io.github.futurelesswindchan.kazefactor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import io.github.futurelesswindchan.kazefactor.engine.vision.RecognizerFactory

/**
 * 从应用 `assets/` 加载识别模板素材（架构方案第 1.2、5、8 节）。
 *
 * - 因子图标原图从 `assets/factors/icons/<iconFileName>` 加载（不能从 `res/drawable` 加载，
 *   因为 drawable 解码会按屏幕密度缩放）。
 * - 帮助图标模板从 `assets/recognition/help.png` 加载（33×38）。
 * - 4 个槽位问号模板从 `assets/recognition/question_slot1~4.png` 加载（各 90×90）。
 */
object AssetRecognitionLoader {

    private const val ICON_DIR = "factors/icons"
    private const val RECOGNITION_DIR = "recognition"
    private const val HELP_ICON_FILE = "$RECOGNITION_DIR/help.png"

    /** 加载帮助图标模板 `assets/recognition/help.png`。 */
    fun loadHelpIcon(context: Context): ArgbImage =
        decodeAssetArgb(context, HELP_ICON_FILE)

    /** 加载 4 个问号模板 `assets/recognition/question_slot1~4.png`。 */
    fun loadQuestionTemplates(context: Context): List<ArgbImage> =
        (1..ReferenceLayout.SLOT_COUNT).map { slot ->
            decodeAssetArgb(context, "$RECOGNITION_DIR/question_slot$slot.png")
        }

    /** 创建全局共享的 [RecognizerFactory]。 */
    fun createRecognizerFactory(
        context: Context,
        database: FactorDatabase
    ): RecognizerFactory {
        val questions = loadQuestionTemplates(context)
        return RecognizerFactory(
            database = database,
            iconLoader = { factor ->
                decodeAssetArgb(context, "$ICON_DIR/${factor.iconFileName}")
            },
            questionImages = questions
        )
    }

    /** 从 `assets/` 读取原始 PNG 文件并解码为不经密度缩放的 [ArgbImage]。 */
    private fun decodeAssetArgb(context: Context, assetPath: String): ArgbImage {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = context.assets.open(assetPath).use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: error("无法解码识别素材：assets/$assetPath")

        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
                ?: error("无法将 assets/$assetPath 转为 ARGB_8888")
        }
        return try {
            val w = argb.width
            val h = argb.height
            val pixels = IntArray(w * h)
            argb.getPixels(pixels, 0, w, 0, 0, w, h)
            ArgbImage(w, h, pixels)
        } finally {
            argb.recycle()
        }
    }
}
