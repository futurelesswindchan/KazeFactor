package io.github.futurelesswindchan.kazefactor.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 配方持久化存储
 * 
 * 使用 DataStore Preferences 存储配方列表的 JSON 序列化数据。
 * 每个配方包含 ID、名称、想要/不想要/可选因子 ID 列表、最小可选数。
 * 
 * 注：因子数据库（FactorDatabase）是只读的，不进持久化。
 */
class RecipeDataStore(private val context: Context) {
    
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    /**
     * 持久化的配方数据结构（仅存储 ID，不存储完整 Factor 对象）
     */
    @Serializable
    data class PersistedRecipe(
        val id: String,
        val name: String,
        val wantedFactorIds: List<Int>,
        val unwantedFactorIds: List<Int>,
        val optionalFactorIds: List<Int>,
        val minOptional: Int,
        val createdAt: Long
    )
    
    @Serializable
    data class RecipeList(
        val recipes: List<PersistedRecipe> = emptyList()
    )
    
    /**
     * 将 domain 层的 Recipe 转换为可持久化的格式
     */
    private fun Recipe.toPersisted(): PersistedRecipe = PersistedRecipe(
        id = id,
        name = name,
        wantedFactorIds = wantedFactorIds.toList(),
        unwantedFactorIds = unwantedFactorIds.toList(),
        optionalFactorIds = optionalFactorIds.toList(),
        minOptional = minOptional,
        createdAt = createdAt
    )
    
    /**
     * 将持久化格式转换回 domain 层的 Recipe（需要传入 FactorDatabase 用于反查因子）
     */
    private fun PersistedRecipe.toRecipe(allFactors: Map<Int, Factor>): Recipe? {
        val wantedIds = wantedFactorIds.toSet()
        val unwantedIds = unwantedFactorIds.toSet()
        val optionalIds = optionalFactorIds.toSet()
        
        // 如果有任何因子 ID 找不到（可能是数据版本不匹配），返回 null
        if (!wantedIds.all { it in allFactors } ||
            !unwantedIds.all { it in allFactors } ||
            !optionalIds.all { it in allFactors }) {
            return null
        }
        
        return Recipe(
            id = id,
            name = name,
            wantedFactorIds = wantedIds,
            unwantedFactorIds = unwantedIds,
            optionalFactorIds = optionalIds,
            minOptional = minOptional,
            createdAt = createdAt
        )
    }
    
    /**
     * 保存配方列表
     */
    suspend fun saveRecipes(recipes: List<Recipe>) {
        val persistedList = RecipeList(recipes.map { it.toPersisted() })
        val jsonString = json.encodeToString(persistedList)
        
        context.dataStore.edit { prefs ->
            prefs[RECIPES_KEY] = jsonString
        }
    }
    
    /**
     * 读取配方列表（需要传入因子数据库用于反查因子）
     * 
     * 返回的 Flow 会过滤掉无法解析的配方（例如因子 ID 不存在的情况）
     */
    fun getRecipes(allFactors: Map<Int, Factor>): Flow<List<Recipe>> {
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[RECIPES_KEY] ?: return@map emptyList()
            
            try {
                val persistedList = json.decodeFromString<RecipeList>(jsonString)
                persistedList.recipes.mapNotNull { it.toRecipe(allFactors) }
            } catch (e: Exception) {
                // JSON 解析失败，返回空列表（例如数据损坏或格式变更）
                emptyList()
            }
        }
    }
    
    /**
     * 添加单个配方（自动生成 UUID）
     */
    suspend fun addRecipe(recipe: Recipe, allFactors: Map<Int, Factor>) {
        val recipeWithId = if (recipe.id.isBlank()) {
            recipe.copy(id = UUID.randomUUID().toString())
        } else {
            recipe
        }
        val currentRecipes = getCurrentRecipes(allFactors)
        saveRecipes(currentRecipes + recipeWithId)
    }
    
    /**
     * 更新配方（通过 ID 匹配）
     */
    suspend fun updateRecipe(id: String, newRecipe: Recipe, allFactors: Map<Int, Factor>) {
        val currentRecipes = getCurrentRecipes(allFactors)
        val updatedList = currentRecipes.map { 
            if (it.id == id) newRecipe.copy(id = id) else it 
        }
        saveRecipes(updatedList)
    }
    
    /**
     * 删除配方（通过 ID 匹配）
     */
    suspend fun deleteRecipe(id: String, allFactors: Map<Int, Factor>) {
        val currentRecipes = getCurrentRecipes(allFactors)
        saveRecipes(currentRecipes.filter { it.id != id })
    }
    
    /**
     * 获取当前配方列表（同步版本，内部使用）
     */
    private suspend fun getCurrentRecipes(allFactors: Map<Int, Factor>): List<Recipe> {
        val jsonString = context.dataStore.data.map { prefs -> 
            prefs[RECIPES_KEY] 
        }.first() ?: ""
        
        if (jsonString.isEmpty()) return emptyList()
        
        return try {
            val persistedList = json.decodeFromString<RecipeList>(jsonString)
            persistedList.recipes.mapNotNull { it.toRecipe(allFactors) }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "recipe_storage")
        private val RECIPES_KEY = stringPreferencesKey("recipes_json")
        
        @Volatile
        private var INSTANCE: RecipeDataStore? = null
        
        fun getInstance(context: Context): RecipeDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RecipeDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
