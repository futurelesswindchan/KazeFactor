package io.github.futurelesswindchan.kazefactor.ui.util

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import io.github.futurelesswindchan.kazefactor.domain.model.Factor

/**
 * 挑战因子图标资源解析工具
 *
 * 根据 [Factor.key] 或 [Factor.iconFileName] 动态映射 `res/drawable` 下的图标资源，
 * 并使用 [FilterQuality.None] 保留像素画原始锐利边缘。
 */
object FactorIconMapper {

    /**
     * 在 Compose 中记住并获取因子的锐利像素风 [Painter]
     *
     * @param factor 挑战因子模型
     * @return 对应的 [Painter]；若资源未找到则返回 null
     */
    @Composable
    fun rememberFactorPainter(factor: Factor): Painter? {
        val context = LocalContext.current
        return remember(factor.key, factor.iconFileName) {
            val resId = getFactorIconRes(
                packageName = context.packageName,
                resources = context.resources,
                factor = factor
            )
            if (resId != 0) {
                val options = BitmapFactory.Options().apply {
                    inScaled = false // 禁止按屏幕密度自动缩放导致像素画模糊
                }
                BitmapFactory.decodeResource(context.resources, resId, options)
                    ?.asImageBitmap()
                    ?.let { BitmapPainter(it, filterQuality = FilterQuality.None) }
            } else {
                null
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    @DrawableRes
    private fun getFactorIconRes(
        packageName: String,
        resources: android.content.res.Resources,
        factor: Factor
    ): Int {
        val resourceName = factor.iconFileName
            ?.substringBeforeLast('.')
            ?.ifBlank { factor.key }
            ?: factor.key

        return resources.getIdentifier(resourceName, "drawable", packageName)
    }
}
