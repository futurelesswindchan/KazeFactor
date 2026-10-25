package io.github.futurelesswindchan.kazefactor.domain.model

import kotlinx.serialization.Serializable

/**
 * 因子数据库：包含所有因子、冲突规则和排他组
 * 
 * @property dataVersion 数据版本号，用于口令码兼容性检查
 * @property gameVersion 对应的游戏版本号（便于维护）
 * @property factors 所有普通挑战因子列表
 * @property conflicts 冲突因子对列表，每个元素是一对不能同时出现的因子ID
 * @property exclusiveGroups 排他组列表，每组内的因子最多只能出现指定数量
 */
@Serializable
data class FactorDatabase(
    val dataVersion: Int,
    val gameVersion: String,
    val factors: List<Factor>,
    val conflicts: List<List<Int>>,
    val exclusiveGroups: List<ExclusiveGroup> = emptyList()
) {
    /**
     * 排他组：定义一组因子中最多只能同时出现的数量
     * 
     * 例如："掉落类因子最多只能出现1个"
     * 
     * @property name 组名（用于错误提示）
     * @property factorIds 该组包含的因子ID列表
     * @property max 最多允许同时出现的数量
     */
    @Serializable
    data class ExclusiveGroup(
        val name: String,
        val factorIds: List<Int>,
        val max: Int
    )
    
    /**
     * 按ID查找因子
     */
    fun getFactorById(id: Int): Factor? {
        return factors.find { it.id == id }
    }
    
    /**
     * 按key查找因子
     */
    fun getFactorByKey(key: String): Factor? {
        return factors.find { it.key == key }
    }
    
    /**
     * 获取所有因子的 ID 集合
     */
    fun getAllFactorIds(): Set<Int> {
        return factors.map { it.id }.toSet()
    }
    
    /**
     * 获取指定类别的所有因子
     */
    fun getFactorsByCategory(category: FactorCategory): List<Factor> {
        return factors.filter { it.category == category }
    }
    
    /**
     * 判断两个因子是否冲突
     */
    fun areConflicting(factorId1: Int, factorId2: Int): Boolean {
        return conflicts.any { pair ->
            (pair[0] == factorId1 && pair[1] == factorId2) ||
            (pair[0] == factorId2 && pair[1] == factorId1)
        }
    }
    
    /**
     * 获取指定因子的所有冲突因子ID
     */
    fun getConflictingFactorIds(factorId: Int): Set<Int> {
        return conflicts
            .filter { factorId in it }
            .flatten()
            .filter { it != factorId }
            .toSet()
    }
    
    /**
     * 检查一组因子是否违反排他组规则
     * 
     * @return 违反的排他组列表（为空表示没有违反）
     */
    fun checkExclusiveGroups(factorIds: Collection<Int>): List<ExclusiveGroup> {
        val violatedGroups = mutableListOf<ExclusiveGroup>()
        
        for (group in exclusiveGroups) {
            val count = factorIds.count { it in group.factorIds }
            if (count > group.max) {
                violatedGroups.add(group)
            }
        }
        
        return violatedGroups
    }
}
