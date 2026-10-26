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
 * 1. 管理配方编辑状态（名称、选中的因子、可选因子最小数）
 * 2. 实时规则验证（RuleEngine）
 * 3. 实时概率计算（ProbabilityCalculator）
 * 4. 保存配方到仓库
 */
class RecipeEditorViewModel(
    private val repository: RecipeRepository,
    private val recipeId: String? = null  // null 表示新建，非 null 表示编辑
) : ViewModel() {
    
    // 因子数据库（按类别分组）
    private val factorsByCategory = repository.getAllFactorsByCategory()
    
    // UI 状态
    private val _recipeName = MutableStateFlow("")
    val recipeName: StateFlow<String> = _recipeName.asStateFlow()
    
    private val _factorSelections = MutableStateFlow<Map<Int, FactorSelectionState>>(emptyMap())
    val factorSelections: StateFlow<Map<Int, FactorSelectionState>> = _factorSelections.asStateFlow()
    
    private val _minOptional = MutableStateFlow(0)
    val minOptional: StateFlow<Int> = _minOptional.asStateFlow()
    
    // 派生状态：当前配方
    val currentRecipe: StateFlow<Recipe> = combine(
        _recipeName,
        _factorSelections,
        _minOptional
    ) { name, selections, minOpt ->
        val wantedIds = selections.filterValues { it == FactorSelectionState.WANTED }.keys
        val unwantedIds = selections.filterValues { it == FactorSelectionState.UNWANTED }.keys
        val optionalIds = selections.filterValues { it == FactorSelectionState.OPTIONAL }.keys
        
        Recipe(
            id = recipeId ?: "",  // 保存时会生成 UUID
            name = name.ifBlank { "未命名配方" },
            wantedFactorIds = wantedIds,
            unwantedFactorIds = unwantedIds,
            optionalFactorIds = optionalIds,
            minOptional = minOpt
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
    
    // 派生状态：规则验证结果
    val violations: StateFlow<List<Violation>> = currentRecipe.map { recipe ->
        repository.validateRecipe(recipe)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    // 派生状态：是否有阻塞性错误
    val hasBlockingErrors: StateFlow<Boolean> = violations.map { list ->
        list.any { it.isBlocking }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )
    
    // 派生状态：命中概率
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
        // 如果是编辑模式，加载已有配方
        if (recipeId != null) {
            loadRecipe(recipeId)
        }
    }
    
    /**
     * 加载已有配方（编辑模式）
     */
    private fun loadRecipe(id: String) {
        viewModelScope.launch {
            repository.getRecipes().first().find { it.id == id }?.let { recipe ->
                _recipeName.value = recipe.name
                _minOptional.value = recipe.minOptional
                
                val selections = mutableMapOf<Int, FactorSelectionState>()
                recipe.wantedFactorIds.forEach { selections[it] = FactorSelectionState.WANTED }
                recipe.unwantedFactorIds.forEach { selections[it] = FactorSelectionState.UNWANTED }
                recipe.optionalFactorIds.forEach { selections[it] = FactorSelectionState.OPTIONAL }
                _factorSelections.value = selections
            }
        }
    }
    
    /**
     * 更新配方名称
     */
    fun updateRecipeName(name: String) {
        _recipeName.value = name
    }
    
    /**
     * 切换因子选择状态（循环：未选中 → 想要 → 不想要 → 可选 → 未选中）
     */
    fun toggleFactorSelection(factorId: Int) {
        val current = _factorSelections.value[factorId] ?: FactorSelectionState.NONE
        val next = when (current) {
            FactorSelectionState.NONE -> FactorSelectionState.WANTED
            FactorSelectionState.WANTED -> FactorSelectionState.UNWANTED
            FactorSelectionState.UNWANTED -> FactorSelectionState.OPTIONAL
            FactorSelectionState.OPTIONAL -> FactorSelectionState.NONE
        }
        
        _factorSelections.value = if (next == FactorSelectionState.NONE) {
            _factorSelections.value - factorId
        } else {
            _factorSelections.value + (factorId to next)
        }
    }
    
    /**
     * 更新可选因子最小数量
     */
    fun updateMinOptional(value: Int) {
        _minOptional.value = value.coerceIn(0, 4)
    }
    
    /**
     * 自动修复违规项
     */
    fun autoFixViolation(violation: Violation) {
        val fixed = repository.autoFixRecipe(currentRecipe.value, violation)
        
        // 更新 UI 状态
        val selections = mutableMapOf<Int, FactorSelectionState>()
        fixed.wantedFactorIds.forEach { selections[it] = FactorSelectionState.WANTED }
        fixed.unwantedFactorIds.forEach { selections[it] = FactorSelectionState.UNWANTED }
        fixed.optionalFactorIds.forEach { selections[it] = FactorSelectionState.OPTIONAL }
        
        _factorSelections.value = selections
        _minOptional.value = fixed.minOptional
    }
    
    /**
     * 保存配方
     */
    suspend fun saveRecipe(): Result<Unit> {
        return try {
            val recipe = currentRecipe.value
            if (recipeId == null) {
                // 新建模式：添加配方
                repository.addRecipe(recipe.copy(id = UUID.randomUUID().toString()))
            } else {
                // 编辑模式：更新配方
                repository.updateRecipe(recipeId, recipe)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取按类别分组的因子
     */
    fun getFactorsByCategory(): Map<String, List<Factor>> {
        return factorsByCategory
    }
}
