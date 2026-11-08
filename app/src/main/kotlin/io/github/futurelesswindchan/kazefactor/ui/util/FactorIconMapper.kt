package io.github.futurelesswindchan.kazefactor.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 挑战因子图标资源解析与内存缓存工具
 *
 * 彻底废弃 `resources.getIdentifier` 反射，直接从 `assets/factors/icons/` 加载无损原始 PNG。
 * 解决 Release 包开启 R8 代码与资源缩减后导致因子图标被误删退化为占位符的问题（架构决策 2026-11-08）。
 *
 * 特性：
 * 1. 资源归一化：与识别模板共享 `assets/factors/icons/`，不受 R8 资源缩减影响；
 * 2. 内存常驻缓存：59 个 90×90 像素的小图常驻内存开销不足 2MB，按文件名或 key 快速命中；
 * 3. 像素画原生锐利：禁止按系统密度缩放，配合 [FilterQuality.None] 实现水晶般的像素风渲染。
 */
object FactorIconMapper {

    private const val ICON_ASSET_DIR = "factors/icons"

    /** 因子图标 ImageBitmap 内存全局缓存，线程安全 */
    private val iconBitmapCache = ConcurrentHashMap<String, ImageBitmap>()

    /**
     * 在 Compose 中记住并获取因子的锐利像素风 [Painter]
     *
     * @param factor 挑战因子模型
     * @return 对应的 [Painter]；若素材不存在或损坏则返回 null
     */
    @Composable
    fun rememberFactorPainter(factor: Factor): Painter? {
        val context = LocalContext.current.applicationContext
        return remember(factor.key, factor.iconFileName) {
            getFactorImageBitmap(context, factor)?.let {
                BitmapPainter(it, filterQuality = FilterQuality.None)
            }
        }
    }

    /**
     * 获取或从 assets 解码因子的 [ImageBitmap]（带全局内存缓存）
     */
    fun getFactorImageBitmap(context: Context, factor: Factor): ImageBitmap? {
        val fileName = factor.iconFileName?.takeIf { it.isNotBlank() } ?: "${factor.key}.png"
        iconBitmapCache[fileName]?.let { return it }
        val loaded = loadAssetImageBitmap(context, fileName)
            ?: loadAssetImageBitmap(context, "${factor.key}.png")
        if (loaded != null) {
            iconBitmapCache.putIfAbsent(fileName, loaded)
        }
        return loaded
    }

    private fun loadAssetImageBitmap(context: Context, fileName: String): ImageBitmap? {
        val assetPath = "$ICON_ASSET_DIR/$fileName"
        return try {
            val stream: InputStream = context.assets.open(assetPath)
            stream.use { input ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false // 禁止按屏幕密度自动缩放导致像素画模糊
                }
                BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
