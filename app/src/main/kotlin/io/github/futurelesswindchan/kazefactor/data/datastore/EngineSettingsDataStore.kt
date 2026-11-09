package io.github.futurelesswindchan.kazefactor.data.datastore

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.futurelesswindchan.kazefactor.engine.loop.RefreshSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 自动刷新设置（供悬浮窗与引擎使用）。
 *
 * 刷新频率固定为每秒 2.5 次（400 ms 间隔），以匹配无障碍服务稳定运行区间。
 *
 * @property frequencyPerSecond 频率档位，默认使用固定常量 [RefreshSettings.DEFAULT_FREQUENCY]
 * @property maxCountLimitEnabled 是否启用总次数上限（默认启用，上限 = 期望次数 × 3 且至少 100）
 * @property customMaxCount 用户自定义的总次数上限（当 > 0 时优先生效；≤ 0 表示使用默认倍数公式）
 * @property vibrateOnHit 命中时是否短震动提醒，默认 true
 * @property lastSelectedRecipeId 上次在悬浮窗中选中的配方 ID，为空时默认选列表第 1 个
 */
data class EngineSettingsPref(
    val frequencyPerSecond: Int = RefreshSettings.DEFAULT_FREQUENCY,
    val maxCountLimitEnabled: Boolean = true,
    val customMaxCount: Int = 0,
    val vibrateOnHit: Boolean = true,
    val lastSelectedRecipeId: String? = null
) {
    /**
     * 按当前设置与配方的期望刷新次数生成 [RefreshSettings]。
     *
     * 默认采用固定的 400 ms 间隔（2.5 次/秒）。
     *
     * @param expectedRefreshCount `ProbabilityCalculator.expectedRefreshCount()` 的结果
     */
    fun toRefreshSettings(expectedRefreshCount: Double): RefreshSettings {
        val maxCount = when {
            !maxCountLimitEnabled -> null
            customMaxCount > 0 -> customMaxCount
            else -> RefreshSettings.defaultMaxRefreshCount(expectedRefreshCount)
        }
        val clampedFreq = frequencyPerSecond.coerceIn(1, 10)
        val intervalOverride = if (clampedFreq == RefreshSettings.DEFAULT_FREQUENCY) {
            RefreshSettings.FIXED_INTERVAL_MS
        } else {
            null
        }
        return RefreshSettings(
            frequencyPerSecond = clampedFreq,
            maxRefreshCount = maxCount,
            intervalOverrideMs = intervalOverride
        )
    }
}

/**
 * 自动刷新引擎设置持久化存储（DataStore 文件 `engine_settings`）。
 */
class EngineSettingsDataStore(private val context: Context) {

    /** 实时监听引擎设置，刷新频率统一为固定常量。 */
    val settingsFlow: Flow<EngineSettingsPref> = context.engineSettingsDataStore.data.map { prefs ->
        EngineSettingsPref(
            frequencyPerSecond = RefreshSettings.DEFAULT_FREQUENCY,
            maxCountLimitEnabled = prefs[KEY_LIMIT_ENABLED] ?: true,
            customMaxCount = (prefs[KEY_CUSTOM_MAX_COUNT] ?: 0).coerceAtLeast(0),
            vibrateOnHit = prefs[KEY_VIBRATE_ON_HIT] ?: true,
            lastSelectedRecipeId = prefs[KEY_LAST_RECIPE_ID]?.takeIf { it.isNotBlank() }
        )
    }

    /** 更新刷新频率档位。 */
    suspend fun setFrequency(frequencyPerSecond: Int) {
        val clamped = frequencyPerSecond.coerceIn(1, 10)
        context.engineSettingsDataStore.edit { prefs ->
            prefs[KEY_FREQUENCY] = clamped
        }
    }

    /** 更新是否启用总次数上限。 */
    suspend fun setMaxCountLimitEnabled(enabled: Boolean) {
        context.engineSettingsDataStore.edit { prefs ->
            prefs[KEY_LIMIT_ENABLED] = enabled
        }
    }

    /** 更新自定义总次数上限（传 0 表示恢复按「期望 × 3（至少 100）」自动计算）。 */
    suspend fun setCustomMaxCount(maxCount: Int) {
        context.engineSettingsDataStore.edit { prefs ->
            prefs[KEY_CUSTOM_MAX_COUNT] = maxCount.coerceAtLeast(0)
        }
    }

    /** 更新命中震动开关。 */
    suspend fun setVibrateOnHit(enabled: Boolean) {
        context.engineSettingsDataStore.edit { prefs ->
            prefs[KEY_VIBRATE_ON_HIT] = enabled
        }
    }

    /** 记录上次选中的配方 ID。 */
    suspend fun setLastSelectedRecipeId(recipeId: String?) {
        context.engineSettingsDataStore.edit { prefs ->
            if (recipeId.isNullOrBlank()) {
                prefs.remove(KEY_LAST_RECIPE_ID)
            } else {
                prefs[KEY_LAST_RECIPE_ID] = recipeId
            }
        }
    }

    companion object {
        private val Context.engineSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "engine_settings"
        )

        private val KEY_FREQUENCY = intPreferencesKey("frequency_per_second")
        private val KEY_LIMIT_ENABLED = booleanPreferencesKey("max_count_limit_enabled")
        private val KEY_CUSTOM_MAX_COUNT = intPreferencesKey("custom_max_count")
        private val KEY_VIBRATE_ON_HIT = booleanPreferencesKey("vibrate_on_hit")
        private val KEY_LAST_RECIPE_ID = stringPreferencesKey("last_selected_recipe_id")

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: EngineSettingsDataStore? = null

        fun getInstance(context: Context): EngineSettingsDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EngineSettingsDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
