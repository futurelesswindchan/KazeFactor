package io.github.futurelesswindchan.kazefactor

import android.app.Application
import io.github.futurelesswindchan.kazefactor.data.repository.RecipeRepository
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.util.AssetFactorLoader

/**
 * KazeFactor 应用入口
 * 
 * 职责：
 * 1. 初始化全局单例（Repository、FactorDatabase）
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
    
    override fun onCreate() {
        super.onCreate()
        // 预加载因子数据库（确保启动时就已加载完成）
        factorDatabase
    }
}
