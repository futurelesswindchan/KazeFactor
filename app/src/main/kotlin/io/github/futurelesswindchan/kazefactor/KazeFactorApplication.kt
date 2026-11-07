package io.github.futurelesswindchan.kazefactor

import android.app.Application
import io.github.futurelesswindchan.kazefactor.data.datastore.CalibrationProfileDataStore
import io.github.futurelesswindchan.kazefactor.data.datastore.EngineSettingsDataStore
import io.github.futurelesswindchan.kazefactor.data.repository.RecipeRepository
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.engine.vision.RecognizerFactory
import io.github.futurelesswindchan.kazefactor.util.AssetFactorLoader
import io.github.futurelesswindchan.kazefactor.util.AssetRecognitionLoader

/**
 * KazeFactor 应用入口
 * 
 * 职责：
 * 1. 初始化全局单例（Repository、FactorDatabase、识别工厂、DataStore）
 * 2. 提供依赖注入（简易手动 DI，不引入 Hilt/Koin）
 */
class KazeFactorApplication : Application() {
    
    /**
     * 因子数据库（从 assets/factors.json 加载，全局共享只读实例）
     */
    val factorDatabase: FactorDatabase by lazy {
        AssetFactorLoader.load(this)
    }
    
    /**
     * 配方仓库（全局单例）
     */
    val recipeRepository: RecipeRepository by lazy {
        RecipeRepository.getInstance(this, factorDatabase)
    }

    /**
     * 自动刷新引擎设置持久化存储（全局单例）
     */
    val engineSettingsDataStore: EngineSettingsDataStore by lazy {
        EngineSettingsDataStore.getInstance(this)
    }

    /**
     * 位置档案持久化存储（全局单例）
     */
    val calibrationProfileDataStore: CalibrationProfileDataStore by lazy {
        CalibrationProfileDataStore.getInstance(this)
    }

    /**
     * 识别器工厂（持有 59 个图标原图与 4 个问号模板，按缩放缓存模板库）
     */
    val recognizerFactory: RecognizerFactory by lazy {
        AssetRecognitionLoader.createRecognizerFactory(this, factorDatabase)
    }
    
    override fun onCreate() {
        super.onCreate()
        // 预加载因子数据库（确保启动时就已加载完成）
        factorDatabase
    }
}
