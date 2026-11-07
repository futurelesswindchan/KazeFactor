package io.github.futurelesswindchan.kazefactor.data.datastore

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream

/**
 * 位置档案持久化存储（DataStore 文件 `calibration_profile`，架构方案第 2.2、3.4.1 节）。
 *
 * 按截图尺寸（`width × height`）为键，每种尺寸保存一份文本，编解码委托给 [CalibrationProfileCodec]。
 * 文本损坏或无法解析时返回 null，调用方按「没有档案」处理（进入自动校准），不会崩溃。
 */
class CalibrationProfileDataStore(private val context: Context) {

    private val codec = CalibrationProfileCodec(
        encodePng = ::encodePngWithBitmap,
        decodePng = ::decodePngWithBitmap
    )

    /**
     * 获取指定截图尺寸（[screenWidth] × [screenHeight]）下的位置档案。
     *
     * @return 存档不存在或已损坏时返回 null
     */
    suspend fun getProfile(screenWidth: Int, screenHeight: Int): CalibrationProfile? {
        val key = keyForSize(screenWidth, screenHeight)
        val raw = context.calibrationDataStore.data.map { prefs -> prefs[key] }.first() ?: return null
        return codec.decode(raw)?.takeIf { it.matchesScreen(screenWidth, screenHeight) }
    }

    /** 监听所有已保存的位置档案（供界面展示当前是否已有档案）。 */
    val allProfilesFlow: Flow<List<CalibrationProfile>> = context.calibrationDataStore.data.map { prefs ->
        prefs.asMap().values
            .filterIsInstance<String>()
            .mapNotNull { codec.decode(it) }
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

    /** 清除全部位置档案（例如用户主动重置）。 */
    suspend fun clearAll() {
        context.calibrationDataStore.edit { prefs ->
            prefs.clear()
        }
    }

    companion object {
        private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "calibration_profile"
        )

        private fun keyForSize(width: Int, height: Int): Preferences.Key<String> =
            stringPreferencesKey("profile_${width}x${height}")

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

        @Volatile
        private var INSTANCE: CalibrationProfileDataStore? = null

        fun getInstance(context: Context): CalibrationProfileDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CalibrationProfileDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
