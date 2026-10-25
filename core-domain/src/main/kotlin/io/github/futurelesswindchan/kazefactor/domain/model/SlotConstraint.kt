package io.github.futurelesswindchan.kazefactor.domain.model

/**
 * 槽位约束：定义了联机模式刷新因子时的17种合法组合模式
 * 
 * 根据元气骑士 Wiki，联机模式的4个因子槽位有严格的类别约束：
 * - 第1位：大恶、中恶、小恶
 * - 第2位：中恶、小恶、中性
 * - 第3位：小恶、中性、小良、大良
 * - 第4位：小良、大良
 * 
 * 并且只有以下17种类别组合是合法的（每个元素代表一个槽位的类别）：
 */
enum class SlotConstraint(
    val slot1: FactorCategory,
    val slot2: FactorCategory,
    val slot3: FactorCategory,
    val slot4: FactorCategory
) {
    // 1. 大恶 + 中恶 + 中性 + 大良
    PATTERN_1(
        FactorCategory.MAJOR_BAD,
        FactorCategory.MODERATE_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 2. 大恶 + 中恶 + 小良 + 大良
    PATTERN_2(
        FactorCategory.MAJOR_BAD,
        FactorCategory.MODERATE_BAD,
        FactorCategory.MINOR_GOOD,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 3. 大恶 + 中恶 + 大良 + 大良
    PATTERN_3(
        FactorCategory.MAJOR_BAD,
        FactorCategory.MODERATE_BAD,
        FactorCategory.MAJOR_GOOD,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 4. 大恶 + 小恶 + 中性 + 大良
    PATTERN_4(
        FactorCategory.MAJOR_BAD,
        FactorCategory.MINOR_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 5. 大恶 + 小恶 + 小良 + 大良
    PATTERN_5(
        FactorCategory.MAJOR_BAD,
        FactorCategory.MINOR_BAD,
        FactorCategory.MINOR_GOOD,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 6. 大恶 + 小恶 + 大良 + 大良
    PATTERN_6(
        FactorCategory.MAJOR_BAD,
        FactorCategory.MINOR_BAD,
        FactorCategory.MAJOR_GOOD,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 7. 大恶 + 中性 + 中性 + 大良
    PATTERN_7(
        FactorCategory.MAJOR_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.NEUTRAL,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 8. 大恶 + 中性 + 小良 + 大良
    PATTERN_8(
        FactorCategory.MAJOR_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.MINOR_GOOD,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 9. 大恶 + 中性 + 小良 + 小良
    PATTERN_9(
        FactorCategory.MAJOR_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.MINOR_GOOD,
        FactorCategory.MINOR_GOOD
    ),
    
    // 10. 中恶 + 中恶 + 中性 + 大良
    PATTERN_10(
        FactorCategory.MODERATE_BAD,
        FactorCategory.MODERATE_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 11. 中恶 + 中恶 + 小良 + 大良
    PATTERN_11(
        FactorCategory.MODERATE_BAD,
        FactorCategory.MODERATE_BAD,
        FactorCategory.MINOR_GOOD,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 12. 中恶 + 小恶 + 中性 + 大良
    PATTERN_12(
        FactorCategory.MODERATE_BAD,
        FactorCategory.MINOR_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 13. 中恶 + 小恶 + 小良 + 大良
    PATTERN_13(
        FactorCategory.MODERATE_BAD,
        FactorCategory.MINOR_BAD,
        FactorCategory.MINOR_GOOD,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 14. 中恶 + 中性 + 小良 + 小良
    PATTERN_14(
        FactorCategory.MODERATE_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.MINOR_GOOD,
        FactorCategory.MINOR_GOOD
    ),
    
    // 15. 中恶 + 中性 + 中性 + 小良
    PATTERN_15(
        FactorCategory.MODERATE_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.NEUTRAL,
        FactorCategory.MINOR_GOOD
    ),
    
    // 16. 小恶 + 小恶 + 小恶 + 大良
    PATTERN_16(
        FactorCategory.MINOR_BAD,
        FactorCategory.MINOR_BAD,
        FactorCategory.MINOR_BAD,
        FactorCategory.MAJOR_GOOD
    ),
    
    // 17. 小恶 + 小恶 + 中性 + 小良
    PATTERN_17(
        FactorCategory.MINOR_BAD,
        FactorCategory.MINOR_BAD,
        FactorCategory.NEUTRAL,
        FactorCategory.MINOR_GOOD
    );
    
    companion object {
        /**
         * 判断给定的4个因子类别组合是否合法
         * 
         * @param slot1Category 第1位因子的类别
         * @param slot2Category 第2位因子的类别
         * @param slot3Category 第3位因子的类别
         * @param slot4Category 第4位因子的类别
         * @return 如果该组合匹配17种模式之一，则返回对应的 SlotConstraint；否则返回 null
         */
        fun findPattern(
            slot1Category: FactorCategory,
            slot2Category: FactorCategory,
            slot3Category: FactorCategory,
            slot4Category: FactorCategory
        ): SlotConstraint? {
            return entries.find {
                it.slot1 == slot1Category &&
                it.slot2 == slot2Category &&
                it.slot3 == slot3Category &&
                it.slot4 == slot4Category
            }
        }
        
        /**
         * 判断给定的4个因子类别组合是否合法
         */
        fun isValidCombination(
            slot1Category: FactorCategory,
            slot2Category: FactorCategory,
            slot3Category: FactorCategory,
            slot4Category: FactorCategory
        ): Boolean {
            return findPattern(slot1Category, slot2Category, slot3Category, slot4Category) != null
        }
    }
}
