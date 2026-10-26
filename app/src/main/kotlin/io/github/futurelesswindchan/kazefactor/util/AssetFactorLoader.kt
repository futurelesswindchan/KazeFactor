package io.github.futurelesswindchan.kazefactor.util

import android.content.Context
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import kotlinx.serialization.json.Json

/**
 * 从 assets 加载因子数据库
 */
object AssetFactorLoader {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * 从 assets/factors.json 加载因子数据库
     */
    fun load(context: Context): FactorDatabase {
        val jsonText = context.assets.open("factors.json").bufferedReader().use { it.readText() }
        return json.decodeFromString<FactorDatabase>(jsonText)
    }
}
