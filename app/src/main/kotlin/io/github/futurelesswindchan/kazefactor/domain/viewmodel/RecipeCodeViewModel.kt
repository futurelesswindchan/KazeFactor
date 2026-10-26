package io.github.futurelesswindchan.kazefactor.domain.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.futurelesswindchan.kazefactor.data.repository.RecipeRepository
import io.github.futurelesswindchan.kazefactor.domain.codec.RecipeCodec
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 配方口令码 ViewModel
 * 
 * 职责：
 * 1. 生成口令码（导出配方）
 * 2. 解析口令码（导入配方）
 * 3. 错误处理
 */
class RecipeCodeViewModel(
    private val repository: RecipeRepository,
    private val database: FactorDatabase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<RecipeCodeUiState>(RecipeCodeUiState.Idle)
    val uiState: StateFlow<RecipeCodeUiState> = _uiState.asStateFlow()
    
    /**
     * 生成口令码（导出配方）
     */
    fun generateCode(recipeId: String) {
        viewModelScope.launch {
            _uiState.value = RecipeCodeUiState.Loading
            
            val recipe = repository.getRecipeById(recipeId)
            if (recipe == null) {
                _uiState.value = RecipeCodeUiState.Error("配方不存在")
                return@launch
            }
            
            when (val result = RecipeCodec.encode(recipe, database)) {
                is RecipeCodec.EncodeResult.Success -> {
                    _uiState.value = RecipeCodeUiState.CodeGenerated(
                        code = result.code,
                        recipeName = recipe.name
                    )
                }
                is RecipeCodec.EncodeResult.Failure -> {
                    _uiState.value = RecipeCodeUiState.Error(result.error.toString())
                }
            }
        }
    }
    
    /**
     * 解析口令码（导入配方）
     * 
     * @param code 口令码字符串
     * @param recipeName 用户输入的配方名（导入时命名）
     */
    fun importCode(code: String, recipeName: String) {
        viewModelScope.launch {
            _uiState.value = RecipeCodeUiState.Loading
            
            if (recipeName.isBlank()) {
                _uiState.value = RecipeCodeUiState.Error("配方名不能为空")
                return@launch
            }
            
            when (val result = RecipeCodec.decode(code.trim(), database)) {
                is RecipeCodec.DecodeResult.Success -> {
                    // 创建新配方
                    val recipe = Recipe(
                        id = "", // Repository 会自动生成 ID
                        name = recipeName,
                        wantedFactorIds = result.wantedFactorIds,
                        unwantedFactorIds = result.unwantedFactorIds,
                        optionalFactorIds = result.optionalFactorIds,
                        minOptional = result.minOptional
                    )
                    
                    repository.saveRecipe(recipe)
                    _uiState.value = RecipeCodeUiState.ImportSuccess(recipeName)
                }
                is RecipeCodec.DecodeResult.Failure -> {
                    _uiState.value = RecipeCodeUiState.Error(result.error.toString())
                }
            }
        }
    }
    
    /**
     * 重置状态
     */
    fun resetState() {
        _uiState.value = RecipeCodeUiState.Idle
    }
}

/**
 * 口令码界面 UI 状态
 */
sealed class RecipeCodeUiState {
    /** 空闲状态 */
    object Idle : RecipeCodeUiState()
    
    /** 加载中 */
    object Loading : RecipeCodeUiState()
    
    /** 口令码已生成 */
    data class CodeGenerated(
        val code: String,
        val recipeName: String
    ) : RecipeCodeUiState()
    
    /** 导入成功 */
    data class ImportSuccess(
        val recipeName: String
    ) : RecipeCodeUiState()
    
    /** 错误 */
    data class Error(
        val message: String
    ) : RecipeCodeUiState()
}
