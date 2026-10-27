package io.github.futurelesswindchan.kazefactor.domain.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.futurelesswindchan.kazefactor.data.repository.RecipeRepository
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 配方列表 ViewModel
 * 
 * 职责：
 * 1. 获取所有配方列表
 * 2. 删除配方
 * 3. 导航到编辑界面
 */
class RecipeListViewModel(
    private val repository: RecipeRepository
) : ViewModel() {
    
    /**
     * 所有配方列表（响应式）
     */
    val recipes: StateFlow<List<Recipe>> = repository.getRecipes()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    /**
     * 删除配方
     */
    fun deleteRecipe(recipeId: String) {
        viewModelScope.launch {
            repository.deleteRecipe(recipeId)
        }
    }
    
    /**
     * 获取配方的简要信息（用于列表展示）
     */
    fun getRecipeSummary(recipe: Recipe): String {
        val wantedCount = recipe.wantedFactorIds.size
        val unwantedCount = recipe.unwantedFactorIds.size
        
        return buildString {
            if (wantedCount > 0) append("想要 $wantedCount 个")
            if (unwantedCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("不要 $unwantedCount 个")
            }
            if (isEmpty()) append("空配方")
        }
    }
    
    /**
     * 计算配方的命中概率（用于列表展示）
     */
    fun getRecipeProbability(recipe: Recipe): Double {
        return if (recipe.wantedFactorIds.isEmpty()) {
            0.0
        } else {
            repository.calculateProbability(recipe)
        }
    }

    /**
     * 根据 ID 获取因子对象（用于卡片图标展示）
     */
    fun getFactorById(id: Int): Factor? {
        return repository.getFactorById(id)
    }
}
