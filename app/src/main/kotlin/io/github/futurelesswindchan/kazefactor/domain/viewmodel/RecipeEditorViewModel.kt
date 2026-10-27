package io.github.futurelesswindchan.kazefactor.domain.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.futurelesswindchan.kazefactor.data.repository.RecipeRepository
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.rule.Violation
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 配方编辑 ViewModel
 * 
 * 职责：
 * 1. 管理配方编辑状态（名称、选中的因子）
 * 2. 实时规则验证（RuleEngine）
 * 3. 实时概率计算（ProbabilityCalculator）
 * 4. 保存配方到仓库
 */
class RecipeEditorViewModel(
    private val repository: RecipeRepository,
    private val recipeId: String? = null
) : ViewModel() {
    
    private val factorsByCategory = repository.getAllFactorsByCategory()
    
    private val _recipeName = MutableStateFlow("")
    val recipeName: StateFlow<String> = _recipeName.asStateFlow()
    
    private val _factorSelections = MutableStateFlow<Map<Int, FactorSelectionState>>(emptyMap())
    val factorSelections: StateFlow<Map<Int, FactorSelectionState>> = _factorSelections.asStateFlow()
    
    val currentRecipe: StateFlow<Recipe> = combine(
        _recipeName,
        _factorSelections
    ) { name, selections ->
        val wantedIds = selections.filterValues { it == FactorSelectionState.WANTED }.keys
        val unwantedIds = selections.filterValues { it == FactorSelectionState.UNWANTED }.keys
        
        Recipe(
            id = recipeId ?: "",
            name = name.ifBlank { "未命名配方" },
            wantedFactorIds = wantedIds,
            unwantedFactorIds = unwantedIds,
            optionalFactorIds = emptySet(),
            minOptional = 0
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = Recipe(
            id = "",
            name = "",
            wantedFactorIds = emptySet(),
            unwantedFactorIds = emptySet(),
            optionalFactorIds = emptySet(),
            minOptional = 0
        )
    )
    
    val violations: StateFlow<List<Violation>> = currentRecipe.map { recipe ->
        repository.validateRecipe(recipe)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    val hasBlockingErrors: StateFlow<Boolean> = violations.map { list ->
        list.any { it.isBlocking }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )
    
    val probability: StateFlow<Double> = currentRecipe.map { recipe ->
        if (recipe.wantedFactorIds.isEmpty()) {
            0.0
        } else {
            repository.calculateProbability(recipe)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0.0
    )
    
    init {
        if (recipeId != null) {
            loadRecipe(recipeId)
        }
    }
    
    private fun loadRecipe(id: String) {
        viewModelScope.launch {
            repository.getRecipes().first().find { it.id == id }?.let { recipe ->
                _recipeName.value = recipe.name
                
                val selections = mutableMapOf<Int, FactorSelectionState>()
                recipe.wantedFactorIds.forEach { selections[it] = FactorSelectionState.WANTED }
                recipe.unwantedFactorIds.forEach { selections[it] = FactorSelectionState.UNWANTED }
                _factorSelections.value = selections
            }
        }
    }
    
    fun updateRecipeName(name: String) {
        _recipeName.value = name
    }
    
    fun toggleFactorSelection(factorId: Int) {
        val current = _factorSelections.value[factorId] ?: FactorSelectionState.NONE
        val next = when (current) {
            FactorSelectionState.NONE -> FactorSelectionState.WANTED
            FactorSelectionState.WANTED -> FactorSelectionState.UNWANTED
            FactorSelectionState.UNWANTED -> FactorSelectionState.NONE
        }
        
        _factorSelections.value = if (next == FactorSelectionState.NONE) {
            _factorSelections.value - factorId
        } else {
            _factorSelections.value + (factorId to next)
        }
    }
    
    fun autoFixViolation(violation: Violation) {
        val fixed = repository.autoFixRecipe(currentRecipe.value, violation)
        
        val selections = mutableMapOf<Int, FactorSelectionState>()
        fixed.wantedFactorIds.forEach { selections[it] = FactorSelectionState.WANTED }
        fixed.unwantedFactorIds.forEach { selections[it] = FactorSelectionState.UNWANTED }
        
        _factorSelections.value = selections
    }
    
    suspend fun saveRecipe(): Result<Unit> {
        return try {
            val recipe = currentRecipe.value
            if (recipeId == null) {
                repository.addRecipe(recipe.copy(id = UUID.randomUUID().toString()))
            } else {
                repository.updateRecipe(recipeId, recipe)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun getFactorsByCategory(): Map<String, List<Factor>> {
        return factorsByCategory
    }
}
