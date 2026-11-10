package io.github.futurelesswindchan.kazefactor.data.datastore

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfile
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfileCodec
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * 位置档案持久化存储（DataStore 文件 `calibration_profile`）。
 *
 * 按截图尺寸（`width × height`）为键，每种尺寸保存一份序列化文本，编解码委托给 [CalibrationProfileCodec]。
 * 当存档损坏、无法解析、横纵缩放不等比，或缩放系数偏离屏幕高度基准比例（`screenHeight / 1080.0`）超过容差时返回 null，
 * 调用方按无档案处理并自动触发重新校准，实现坏档案无感淘汰。
 */
class CalibrationProfileDataStore(private val context: Context) {

    private val codec = CalibrationProfileCodec(
        encodePng = ::encodePngWithBitmap,
        decodePng = ::decodePngWithBitmap
    )

    /**
     * 获取指定截图尺寸（[screenWidth] × [screenHeight]）下的有效位置档案。
     *
     * 若存档不存在、已损坏、横纵缩放相对偏差超过 [MAX_SCALE_ASPECT_DIFF]（1%），
     * 或 `scaleY` 与屏幕高度基准比例 `screenHeight / 1080.0` 的相对偏差超过 [MAX_HEIGHT_SCALE_DEVIATION]（4%），均返回 null。
     */
    suspend fun getProfile(screenWidth: Int, screenHeight: Int): CalibrationProfile? {
        val key = keyForSize(screenWidth, screenHeight)
        val raw = context.calibrationDataStore.data.map { prefs -> prefs[key] }.first() ?: return null
        return codec.decode(raw)?.takeIf { profile ->
            profile.matchesScreen(screenWidth, screenHeight) && isUniformScale(profile)
        }
    }

    /** 监听所有已保存的有效位置档案。 */
    val allProfilesFlow: Flow<List<CalibrationProfile>> = context.calibrationDataStore.data.map { prefs ->
        prefs.asMap().values
            .filterIsInstance<String>()
            .mapNotNull { codec.decode(it) }
            .filter(::isUniformScale)
    }

    /**
     * 保存位置档案（按档案的 `screenWidth × screenHeight` 覆盖同尺寸旧档案）。
     */
    suspend fun saveProfile(profile: CalibrationProfile) {
        val key = keyForSize(profile.screenWidth, profile.screenHeight)
        val encoded = codec.encode(profile)
        context.calibrationDataStore.edit { prefs ->
            prefs[key] = encoded
        }
    }

    /** 清除指定截图尺寸的位置档案。 */
    suspend fun clearProfile(screenWidth: Int, screenHeight: Int) {
        val key = keyForSize(screenWidth, screenHeight)
        context.calibrationDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    /** 清除全部位置档案。 */
    suspend fun clearAll() {
        context.calibrationDataStore.edit { prefs ->
            prefs.clear()
        }
    }

    companion object {
        /** 允许的横纵缩放最大相对偏差（1%），超出此阈值的非等比档案自动作废。 */
        const val MAX_SCALE_ASPECT_DIFF = 0.01

        /** 允许的纵向缩放系数与屏幕高度基准比例（`screenHeight / 1080.0`）的最大相对偏差（4%），超出此阈值的坏档案自动淘汰。 */
        const val MAX_HEIGHT_SCALE_DEVIATION = 0.04

        private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "calibration_profile"
        )

        private fun keyForSize(width: Int, height: Int): Preferences.Key<String> =
            stringPreferencesKey("profile_${width}x${height}")

        /**
         * 校验位置档案是否满足等比缩放约束与屏幕高度基准比例约束。
         *
         * 1. 横向与纵向缩放系数的相对偏差不得超过 [MAX_SCALE_ASPECT_DIFF]（1%）；
         * 2. 纵向缩放系数 `scaleY` 与屏幕高度基准比例 `screenHeight / 1080.0` 的相对偏差不得超过 [MAX_HEIGHT_SCALE_DEVIATION]（4%）。
         */
        internal fun isUniformScale(profile: CalibrationProfile): Boolean {
            if (profile.scaleY <= 0.0 || profile.screenHeight <= 0) return false
            val aspectDiff = abs(profile.scaleX - profile.scaleY) / profile.scaleY
            if (aspectDiff > MAX_SCALE_ASPECT_DIFF) return false
            val expectedHeightScale = profile.screenHeight.toDouble() / ReferenceLayout.HEIGHT
            val heightScaleDiff = abs(profile.scaleY - expectedHeightScale) / expectedHeightScale
            return heightScaleDiff <= MAX_HEIGHT_SCALE_DEVIATION
        }

        /** 使用 Android [Bitmap] 将 [ArgbImage] 编码为无损 PNG 字节。 */
        fun encodePngWithBitmap(image: ArgbImage): ByteArray {
            val bitmap = Bitmap.createBitmap(
                image.pixels,
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            return try {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        /** 使用 Android [BitmapFactory] 将 PNG 字节解码为 [ArgbImage]。 */
        fun decodePngWithBitmap(bytes: ByteArray): ArgbImage {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw IllegalArgumentException("无法解码 PNG 字节")
            val argbBitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
                    ?: throw IllegalArgumentException("无法转换为 ARGB_8888")
            }
            return try {
                val w = argbBitmap.width
                val h = argbBitmap.height
                val pixels = IntArray(w * h)
                argbBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                ArgbImage(w, h, pixels)
            } finally {
                argbBitmap.recycle()
            }
        }

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: CalibrationProfileDataStore? = null

        fun getInstance(context: Context): CalibrationProfileDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CalibrationProfileDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
