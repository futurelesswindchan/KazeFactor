package io.github.futurelesswindchan.kazefactor.domain

import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 因子数据加载测试
 * 
 * 验证 factors.json 能正确解析，并且所有59个因子都存在
 */
class FactorDatabaseTest {
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    /**
     * 从测试资源加载 factors.json
     */
    private fun loadFactorDatabase(): FactorDatabase {
        val jsonText = this::class.java.classLoader
            ?.getResource("factors.json")
            ?.readText()
            ?: error("无法读取 factors.json")
        
        return json.decodeFromString<FactorDatabase>(jsonText)
    }
    
    @Test
    fun `测试加载因子数据库`() {
        val db = loadFactorDatabase()
        
        assertNotNull(db, "因子数据库不应为空")
        assertTrue(db.factors.isNotEmpty(), "因子列表不应为空")
    }
    
    @Test
    fun `测试因子数量为59个`() {
        val db = loadFactorDatabase()
        
        assertEquals(59, db.factors.size, "普通挑战因子应该有59个")
    }
    
    @Test
    fun `测试因子ID唯一且连续`() {
        val db = loadFactorDatabase()
        
        val ids = db.factors.map { it.id }.sorted()
        val expectedIds = (1..59).toList()
        
        assertEquals(expectedIds, ids, "因子ID应该是1到59的连续整数")
    }
    
    @Test
    fun `测试因子key唯一`() {
        val db = loadFactorDatabase()
        
        val keys = db.factors.map { it.key }
        val uniqueKeys = keys.toSet()
        
        assertEquals(keys.size, uniqueKeys.size, "因子key不应有重复")
    }
    
    @Test
    fun `测试各类别因子数量`() {
        val db = loadFactorDatabase()
        
        val countByCategory = db.factors.groupBy { it.category }
            .mapValues { it.value.size }
        
        // 根据 wiki 统计的数量
        assertEquals(6, countByCategory[io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory.MAJOR_BAD], "大恶因子应该有6个")
        assertEquals(14, countByCategory[io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory.MODERATE_BAD], "中恶因子应该有14个")
        assertEquals(8, countByCategory[io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory.MINOR_BAD], "小恶因子应该有8个")
        assertEquals(15, countByCategory[io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory.NEUTRAL], "中性因子应该有15个")
        assertEquals(7, countByCategory[io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory.MINOR_GOOD], "小良因子应该有7个")
        assertEquals(9, countByCategory[io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory.MAJOR_GOOD], "大良因子应该有9个")
    }
    
    @Test
    fun `测试冲突规则数量`() {
        val db = loadFactorDatabase()
        
        // wiki 列出了11组冲突
        assertEquals(11, db.conflicts.size, "应该有11组冲突因子对")
    }
    
    @Test
    fun `测试冲突因子ID有效`() {
        val db = loadFactorDatabase()
        val validIds = db.factors.map { it.id }.toSet()
        
        for (conflictPair in db.conflicts) {
            assertEquals(2, conflictPair.size, "每个冲突对应该包含2个因子ID")
            assertTrue(conflictPair[0] in validIds, "冲突因子ID ${conflictPair[0]} 不在有效范围内")
            assertTrue(conflictPair[1] in validIds, "冲突因子ID ${conflictPair[1]} 不在有效范围内")
        }
    }
    
    @Test
    fun `测试查询因子功能`() {
        val db = loadFactorDatabase()
        
        // 测试按ID查找
        val factor1 = db.getFactorById(51)
        assertNotNull(factor1, "应该能找到ID为51的因子")
        assertEquals("infinite_energy", factor1.key)
        assertEquals("无限能量", factor1.name)
        
        // 测试按key查找
        val factor2 = db.getFactorByKey("double_joy")
        assertNotNull(factor2, "应该能找到key为double_joy的因子")
        assertEquals(1, factor2.id)
        assertEquals("双倍快乐", factor2.name)
    }
    
    @Test
    fun `测试冲突检测功能`() {
        val db = loadFactorDatabase()
        
        // 巨大化(40) 与 缩小隧道(41) 冲突
        assertTrue(db.areConflicting(40, 41), "巨大化和缩小隧道应该冲突")
        assertTrue(db.areConflicting(41, 40), "冲突检测应该是对称的")
        
        // 无限能量(51) 与 能量衰弱(23) 冲突
        assertTrue(db.areConflicting(51, 23), "无限能量和能量衰弱应该冲突")
        
        // 双倍快乐(1) 与 脆皮(3) 不冲突
        assertTrue(!db.areConflicting(1, 3), "双倍快乐和脆皮不应该冲突")
    }
    
    @Test
    fun `测试获取冲突因子ID`() {
        val db = loadFactorDatabase()
        
        // 巨大化(40) 的冲突因子应该包含 缩小隧道(41)
        val conflicts40 = db.getConflictingFactorIds(40)
        assertTrue(41 in conflicts40, "巨大化的冲突因子应该包含缩小隧道")
        
        // 无限能量(51) 的冲突因子应该包含 能量衰弱(23)
        val conflicts51 = db.getConflictingFactorIds(51)
        assertTrue(23 in conflicts51, "无限能量的冲突因子应该包含能量衰弱")
    }
    
    @Test
    fun `测试所有因子都有描述`() {
        val db = loadFactorDatabase()
        
        for (factor in db.factors) {
            assertTrue(factor.description.isNotBlank(), "因子 ${factor.name} 的描述不应为空")
        }
    }
}
