package io.github.futurelesswindchan.kazefactor.domain.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.futurelesswindchan.kazefactor.data.repository.RecipeRepository

/**
 * ViewModel 工厂
 * 
 * 职责：
 * 1. 为所有 ViewModel 提供依赖注入
 * 2. 统一管理 Repository 和 FactorDatabase 实例
 */
class ViewModelFactory(
    private val repository: RecipeRepository,
    private val database: io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase,
    private val recipeId: String? = null
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(RecipeListViewModel::class.java) -> {
                RecipeListViewModel(repository) as T
            }
            modelClass.isAssignableFrom(RecipeEditorViewModel::class.java) -> {
                RecipeEditorViewModel(repository, recipeId) as T
            }
            modelClass.isAssignableFrom(RecipeCodeViewModel::class.java) -> {
                RecipeCodeViewModel(repository, database) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
