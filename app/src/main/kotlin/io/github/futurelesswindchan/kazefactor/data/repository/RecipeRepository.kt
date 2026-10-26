package io.github.futurelesswindchan.kazefactor.data.repository

import android.content.Context
import io.github.futurelesswindchan.kazefactor.data.datastore.RecipeDataStore
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.rule.ProbabilityCalculator
import io.github.futurelesswindchan.kazefactor.domain.rule.RuleEngine
import io.github.futurelesswindchan.kazefactor.domain.rule.Violation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 配方数据仓库
 * 
 * 职责：
 * 1. 封装 RecipeDataStore 的数据访问
 * 2. 提供因子数据库查询（硬编码数据，不需持久化）
 * 3. 集成 RuleEngine 进行规则验证
 * 4. 作为 ViewModel 的单一数据源
 */
class RecipeRepository private constructor(
    private val dataStore: RecipeDataStore,
    private val factorDatabase: FactorDatabase
) {
    
    /**
     * 规则引擎实例（需要 ProbabilityCalculator）
     */
    private val ruleEngine: RuleEngine by lazy {
        RuleEngine(factorDatabase, ProbabilityCalculator(factorDatabase))
    }
    
    /**
     * 概率计算器实例
     */
    private val probabilityCalculator: ProbabilityCalculator by lazy {
        ProbabilityCalculator(factorDatabase)
    }
    
    /**
     * 获取所有因子（按类别分组）
     */
    fun getAllFactorsByCategory(): Map<String, List<Factor>> {
        return factorDatabase.factors.groupBy { it.category.name }
    }
    
    /**
     * 获取所有因子（Map 形式，用于快速查找）
     */
    fun getAllFactorsMap(): Map<Int, Factor> {
        return factorDatabase.factors.associateBy { it.id }
    }
    
    /**
     * 根据 ID 查找因子
     */
    fun getFactorById(id: Int): Factor? {
        return factorDatabase.getFactorById(id)
    }
    
    /**
     * 获取所有配方列表（响应式）
     */
    fun getRecipes(): Flow<List<Recipe>> {
        return dataStore.getRecipes(getAllFactorsMap())
    }
    
    /**
     * 根据 ID 获取单个配方
     */
    suspend fun getRecipeById(id: String): Recipe? {
        // DataStore 只提供 Flow<List<Recipe>>，需要从列表中查找
        return dataStore.getRecipes(getAllFactorsMap())
            .first()
            .find { it.id == id }
    }
    
    /**
     * 添加配方
     */
    suspend fun addRecipe(recipe: Recipe) {
        dataStore.addRecipe(recipe, getAllFactorsMap())
    }
    
    /**
     * 更新配方
     */
    suspend fun updateRecipe(id: String, newRecipe: Recipe) {
        dataStore.updateRecipe(id, newRecipe, getAllFactorsMap())
    }
    
    /**
     * 保存配方（新增或更新）
     * 
     * 如果 recipe.id 为空或不存在，则新增；否则更新
     */
    suspend fun saveRecipe(recipe: Recipe) {
        if (recipe.id.isEmpty()) {
            // 新增配方
            addRecipe(recipe)
        } else {
            // 更新配方
            val existingRecipe = getRecipeById(recipe.id)
            if (existingRecipe != null) {
                updateRecipe(recipe.id, recipe)
            } else {
                addRecipe(recipe)
            }
        }
    }
    
    /**
     * 删除配方
     */
    suspend fun deleteRecipe(id: String) {
        dataStore.deleteRecipe(id, getAllFactorsMap())
    }
    
    /**
     * 验证配方规则
     * 
     * @return 所有违规项（ERROR 和 WARNING）
     */
    fun validateRecipe(recipe: Recipe): List<Violation> {
        return ruleEngine.validate(recipe)
    }
    
    /**
     * 获取配方的阻塞性错误（会阻止开始刷新的错误）
     */
    fun getBlockingErrors(recipe: Recipe): List<Violation> {
        return ruleEngine.validate(recipe).filter { it.isBlocking }
    }
    
    /**
     * 获取配方的非阻塞性警告
     */
    fun getWarnings(recipe: Recipe): List<Violation> {
        return ruleEngine.validate(recipe).filter { !it.isBlocking }
    }
    
    /**
     * 自动修正配方（应用规则引擎的建议）
     * 
     * @param recipe 待修复的配方
     * @param violation 需要修复的违规项
     * @return 修复后的配方
     */
    fun autoFixRecipe(recipe: Recipe, violation: Violation): Recipe {
        return ruleEngine.autoFix(recipe, violation)
    }
    
    /**
     * 计算配方的命中概率
     */
    fun calculateProbability(recipe: Recipe): Double {
        return probabilityCalculator.calculate(recipe)
    }
    
    companion object {
        @Volatile
        private var INSTANCE: RecipeRepository? = null
        
        /**
         * 获取单例实例
         * 
         * @param context 用于初始化 DataStore
         * @param factorDatabase 因子数据库（从 assets 加载）
         */
        fun getInstance(context: Context, factorDatabase: FactorDatabase): RecipeRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RecipeRepository(
                    dataStore = RecipeDataStore.getInstance(context),
                    factorDatabase = factorDatabase
                ).also { INSTANCE = it }
            }
        }
    }
}
