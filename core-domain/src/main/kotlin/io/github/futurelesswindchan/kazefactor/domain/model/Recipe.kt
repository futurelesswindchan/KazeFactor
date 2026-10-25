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
    val wantedFactorIds: List<Int>,
    val unwantedFactorIds: List<Int> = emptyList(),
    val optionalFactorIds: List<Int> = emptyList(),
    val minOptional: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(wantedFactorIds.size <= 4) { 
            "想要的因子数量不能超过4个" 
        }
        require(wantedFactorIds.toSet().size == wantedFactorIds.size) {
            "想要的因子列表不能有重复"
        }
        require(unwantedFactorIds.toSet().size == unwantedFactorIds.size) {
            "不想要的因子列表不能有重复"
        }
        require(optionalFactorIds.toSet().size == optionalFactorIds.size) {
            "可选因子列表不能有重复"
        }
        require(minOptional >= 0) {
            "可选因子的最小数量不能为负数"
        }
        require(minOptional <= optionalFactorIds.size) {
            "可选因子的最小数量不能超过可选因子总数"
        }
        
        // wanted、unwanted、optional 三个集合不能有交集
        val wanted = wantedFactorIds.toSet()
        val unwanted = unwantedFactorIds.toSet()
        val optional = optionalFactorIds.toSet()
        
        require((wanted intersect unwanted).isEmpty()) {
            "想要和不想要的因子不能有重叠"
        }
        require((wanted intersect optional).isEmpty()) {
            "想要和可选的因子不能有重叠"
        }
        require((unwanted intersect optional).isEmpty()) {
            "不想要和可选的因子不能有重叠"
        }
    }
    
    /**
     * 获取该配方涉及的所有因子ID（去重）
     */
    fun getAllFactorIds(): Set<Int> {
        return wantedFactorIds.toSet() + unwantedFactorIds.toSet() + optionalFactorIds.toSet()
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
