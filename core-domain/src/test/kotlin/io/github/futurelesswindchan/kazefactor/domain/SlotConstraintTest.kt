package io.github.futurelesswindchan.kazefactor.domain

import io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory.*
import io.github.futurelesswindchan.kazefactor.domain.model.SlotConstraint
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 槽位约束测试
 * 
 * 验证17种合法组合模式的定义是否正确
 */
class SlotConstraintTest {
    
    @Test
    fun `测试17种合法组合都能找到`() {
        // 按照 wiki 列出的17种组合逐一验证
        
        // 1. 大恶＋中恶＋中性＋大良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, MODERATE_BAD, NEUTRAL, MAJOR_GOOD))
        
        // 2. 大恶＋中恶＋小良＋大良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, MODERATE_BAD, MINOR_GOOD, MAJOR_GOOD))
        
        // 3. 大恶＋中恶＋大良＋大良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, MODERATE_BAD, MAJOR_GOOD, MAJOR_GOOD))
        
        // 4. 大恶＋小恶＋中性＋大良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, MINOR_BAD, NEUTRAL, MAJOR_GOOD))
        
        // 5. 大恶＋小恶＋小良＋大良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, MINOR_BAD, MINOR_GOOD, MAJOR_GOOD))
        
        // 6. 大恶＋小恶＋大良＋大良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, MINOR_BAD, MAJOR_GOOD, MAJOR_GOOD))
        
        // 7. 大恶＋中性＋中性＋大良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, NEUTRAL, NEUTRAL, MAJOR_GOOD))
        
        // 8. 大恶＋中性＋小良＋大良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, NEUTRAL, MINOR_GOOD, MAJOR_GOOD))
        
        // 9. 大恶＋中性＋小良＋小良
        assertNotNull(SlotConstraint.findPattern(MAJOR_BAD, NEUTRAL, MINOR_GOOD, MINOR_GOOD))
        
        // 10. 中恶＋中恶＋中性＋大良
        assertNotNull(SlotConstraint.findPattern(MODERATE_BAD, MODERATE_BAD, NEUTRAL, MAJOR_GOOD))
        
        // 11. 中恶＋中恶＋小良＋大良
        assertNotNull(SlotConstraint.findPattern(MODERATE_BAD, MODERATE_BAD, MINOR_GOOD, MAJOR_GOOD))
        
        // 12. 中恶＋小恶＋中性＋大良
        assertNotNull(SlotConstraint.findPattern(MODERATE_BAD, MINOR_BAD, NEUTRAL, MAJOR_GOOD))
        
        // 13. 中恶＋小恶＋小良＋大良
        assertNotNull(SlotConstraint.findPattern(MODERATE_BAD, MINOR_BAD, MINOR_GOOD, MAJOR_GOOD))
        
        // 14. 中恶＋中性＋小良＋小良
        assertNotNull(SlotConstraint.findPattern(MODERATE_BAD, NEUTRAL, MINOR_GOOD, MINOR_GOOD))
        
        // 15. 中恶＋中性＋中性＋小良
        assertNotNull(SlotConstraint.findPattern(MODERATE_BAD, NEUTRAL, NEUTRAL, MINOR_GOOD))
        
        // 16. 小恶＋小恶＋小恶＋大良
        assertNotNull(SlotConstraint.findPattern(MINOR_BAD, MINOR_BAD, MINOR_BAD, MAJOR_GOOD))
        
        // 17. 小恶＋小恶＋中性＋小良
        assertNotNull(SlotConstraint.findPattern(MINOR_BAD, MINOR_BAD, NEUTRAL, MINOR_GOOD))
    }
    
    @Test
    fun `测试不合法的组合返回null`() {
        // wiki 里有玩家声称遇到过"大恶＋中性＋大良＋大良"，但这个组合不存在
        assertNull(SlotConstraint.findPattern(MAJOR_BAD, NEUTRAL, MAJOR_GOOD, MAJOR_GOOD))
        
        // 其他随机组合也应该返回 null
        assertNull(SlotConstraint.findPattern(MAJOR_GOOD, MAJOR_GOOD, MAJOR_GOOD, MAJOR_GOOD))
        assertNull(SlotConstraint.findPattern(MINOR_BAD, MINOR_GOOD, MINOR_BAD, MINOR_GOOD))
    }
    
    @Test
    fun `测试isValidCombination方法`() {
        // 合法组合
        assertTrue(SlotConstraint.isValidCombination(MAJOR_BAD, MODERATE_BAD, NEUTRAL, MAJOR_GOOD))
        
        // 不合法组合
        assertTrue(!SlotConstraint.isValidCombination(MAJOR_BAD, NEUTRAL, MAJOR_GOOD, MAJOR_GOOD))
    }
    
    @Test
    fun `测试17种模式数量正确`() {
        // 枚举应该恰好包含17个模式
        kotlin.test.assertEquals(17, SlotConstraint.entries.size, "应该定义17种槽位组合模式")
    }
}
