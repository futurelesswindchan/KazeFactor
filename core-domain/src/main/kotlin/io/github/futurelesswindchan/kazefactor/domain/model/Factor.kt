package io.github.futurelesswindchan.kazefactor.domain.model

import kotlinx.serialization.Serializable

/**
 * 因子类别枚举
 * 
 * 按照游戏规则，不同类别的因子在槽位中的出现位置有严格限制。
 * 这个枚举同时定义了类别的难度权重，用于后续的配方匹配和概率计算。
 */
@Serializable
enum class FactorCategory(val weight: Int) {
    /** 大恶因子：只能出现在第1位 */
    MAJOR_BAD(weight = 3),
    
    /** 中恶因子：只能出现在第1、2位 */
    MODERATE_BAD(weight = 2),
    
    /** 小恶因子：只能出现在第1、2、3位 */
    MINOR_BAD(weight = 1),
    
    /** 中性因子：只能出现在第2、3位 */
    NEUTRAL(weight = 0),
    
    /** 小良因子：只能出现在第3、4位 */
    MINOR_GOOD(weight = -1),
    
    /** 大良因子：只能出现在第3、4位 */
    MAJOR_GOOD(weight = -2);
}

/**
 * 挑战因子数据模型
 * 
 * @property id 因子唯一ID，用于配方存储和口令码编码，一旦发布永不改变
 * @property key 因子的英文短名（kebab-case），用于图标文件命名和日志输出
 * @property name 因子的中文名称，显示在界面上
 * @property description 因子效果描述，用于悬浮窗的详细信息展示
 * @property category 因子类别，决定了它在槽位中的出现位置
 * @property iconFileName 图标文件名（可选，默认为 "${key}.png"）
 */
@Serializable
data class Factor(
    val id: Int,
    val key: String,
    val name: String,
    val description: String,
    val category: FactorCategory,
    val iconFileName: String? = null
) {
    /**
     * 获取图标文件名
     * 如果未显式指定，则使用 key 作为文件名
     */
    fun getIconFile(): String = iconFileName ?: "$key.png"
    
    /**
     * 判断该因子是否可以出现在指定槽位
     * 
     * @param slotIndex 槽位索引（1~4）
     * @return 如果该因子可以出现在该槽位则返回 true
     */
    fun canAppearInSlot(slotIndex: Int): Boolean {
        require(slotIndex in 1..4) { "槽位索引必须在 1~4 之间" }
        
        return when (category) {
            FactorCategory.MAJOR_BAD -> slotIndex == 1
            FactorCategory.MODERATE_BAD -> slotIndex in 1..2
            FactorCategory.MINOR_BAD -> slotIndex in 1..3
            FactorCategory.NEUTRAL -> slotIndex in 2..3
            FactorCategory.MINOR_GOOD -> slotIndex in 3..4
            FactorCategory.MAJOR_GOOD -> slotIndex in 3..4
        }
    }
}
