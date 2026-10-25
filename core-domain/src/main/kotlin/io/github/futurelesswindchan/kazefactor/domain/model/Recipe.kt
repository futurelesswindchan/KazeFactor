package io.github.futurelesswindchan.kazefactor.domain.model

import kotlinx.serialization.Serializable

/**
 * 配方数据模型
 * 
 * 配方定义了玩家想要刷出的因子组合。支持三种匹配语义：
 * - wanted: 必须全部出现
 * - unwanted: 一个都不能出现
 * - optional: 至少出现 minOptional 个
 * 
 * @property id 配方唯一ID（UUID或数据库自增ID）
 * @property name 配方名称（用户自定义，如"超简单因子""刷钱因子"）
 * @property wantedFactorIds 想要的因子ID列表（这些因子必须全部出现）
 * @property unwantedFactorIds 不想要的因子ID列表（这些因子一个都不能出现）
 * @property optionalFactorIds 可选因子ID列表（这些因子中至少出现 minOptional 个）
 * @property minOptional 可选因子的最小出现数量（默认为0，表示不关心可选因子）
 * @property createdAt 配方创建时间戳（毫秒）
 */
@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val wantedFactorIds: Set<Int>,
    val unwantedFactorIds: Set<Int> = emptySet(),
    val optionalFactorIds: Set<Int> = emptySet(),
    val minOptional: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    // 注意：这里只拦截"结构性"错误（负数）。
    // 超过4个、想要/不想要重叠、冲突等"业务规则"允许存在于草稿中，
    // 由 RuleEngine.validate() 检测并给出自动纠正建议，否则 UI 无法展示这些提示。
    init {
        require(minOptional >= 0) {
            "可选因子的最小数量不能为负数"
        }
    }
    
    /**
     * 获取该配方涉及的所有因子ID（去重）
     */
    fun getAllFactorIds(): Set<Int> {
        return wantedFactorIds + unwantedFactorIds + optionalFactorIds
    }
    
    /**
     * 判断给定的4个因子是否匹配该配方
     * 
     * @param slot1FactorId 第1个槽位的因子ID
     * @param slot2FactorId 第2个槽位的因子ID
     * @param slot3FactorId 第3个槽位的因子ID
     * @param slot4FactorId 第4个槽位的因子ID
     * @return 如果匹配则返回 true
     */
    fun matches(
        slot1FactorId: Int,
        slot2FactorId: Int,
        slot3FactorId: Int,
        slot4FactorId: Int
    ): Boolean {
        val drawnFactors = setOf(slot1FactorId, slot2FactorId, slot3FactorId, slot4FactorId)
        
        // 检查"想要的"是否全部出现
        if (!drawnFactors.containsAll(wantedFactorIds)) {
            return false
        }
        
        // 检查"不想要的"是否一个都没出现
        if (drawnFactors.any { it in unwantedFactorIds }) {
            return false
        }
        
        // 检查"可选的"是否至少出现 minOptional 个
        val optionalCount = drawnFactors.count { it in optionalFactorIds }
        if (optionalCount < minOptional) {
            return false
        }
        
        return true
    }
}
