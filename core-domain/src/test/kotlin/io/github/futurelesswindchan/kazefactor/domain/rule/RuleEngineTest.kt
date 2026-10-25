package io.github.futurelesswindchan.kazefactor.domain.rule

import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.testutil.loadFactorDatabase
import kotlin.test.*

class RuleEngineTest {

    private val db = loadFactorDatabase()
    private val engine = RuleEngine(db, ProbabilityCalculator(db))

    private fun recipe(
        wanted: Set<Int> = emptySet(),
        unwanted: Set<Int> = emptySet(),
        optional: Set<Int> = emptySet(),
        minOptional: Int = 0
    ) = Recipe(
        id = "t",
        name = "测试",
        wantedFactorIds = wanted,
        unwantedFactorIds = unwanted,
        optionalFactorIds = optional,
        minOptional = minOptional
    )

    @Test
    fun `R1-想要超过4个`() {
        val v = engine.validate(recipe(wanted = setOf(1, 7, 21, 44, 51)))
        val hit = v.filterIsInstance<Violation.TooManyWanted>().single()
        assertEquals(5, hit.count)
    }

    @Test
    fun `R2-想要与不想要重叠`() {
        val v = engine.validate(recipe(wanted = setOf(1, 51), unwanted = setOf(51, 2)))
        val hit = v.filterIsInstance<Violation.WantedUnwantedOverlap>().single()
        assertEquals(setOf(51), hit.overlappingIds)
    }

    @Test
    fun `R3-想要的因子冲突`() {
        // 巨大化(40) 与 缩小隧道(41)
        val v = engine.validate(recipe(wanted = setOf(40, 41)))
        assertTrue(v.any { it is Violation.ConflictInWanted })
    }

    @Test
    fun `R4-排除太多凑不出结果`() {
        // 排除全部恶性因子，第1位就无因子可出
        val allBad = (1..28).toSet()
        val v = engine.validate(recipe(unwanted = allBad))
        assertTrue(v.any { it is Violation.InsufficientPool }, "实际：$v")
    }

    @Test
    fun `R5-四个大良不可能`() {
        val v = engine.validate(recipe(wanted = setOf(51, 52, 53, 54)))
        assertTrue(v.any { it is Violation.ImpossibleCombination }, "实际：$v")
    }

    @Test
    fun `R5-两个大恶不可能`() {
        val v = engine.validate(recipe(wanted = setOf(1, 2)))
        assertTrue(v.any { it is Violation.ImpossibleCombination }, "实际：$v")
    }

    @Test
    fun `R6-低命中率提示`() {
        // 指定四个具体因子，命中率必然很低
        val v = engine.validate(recipe(wanted = setOf(5, 7, 29, 51)), lowProbabilityThreshold = 0.001)
        assertTrue(v.any { it is Violation.LowProbability }, "实际：$v")
    }

    @Test
    fun `R6-低命中率只提示不阻塞`() {
        val r = recipe(wanted = setOf(5, 7, 29, 51))
        val v = engine.validate(r, lowProbabilityThreshold = 0.001)
        assertTrue(v.all { it.severity == Violation.Severity.WARNING }, "实际：$v")
        assertTrue(engine.canStart(r, lowProbabilityThreshold = 0.001), "低概率不应阻止开始")
    }

    @Test
    fun `R5-不可能组合会阻塞开始`() {
        assertFalse(engine.canStart(recipe(wanted = setOf(51, 52, 53, 54))))
    }

    @Test
    fun `合法配方-无限能量加特卖日`() {
        val v = engine.validate(recipe(wanted = setOf(51, 52)), lowProbabilityThreshold = 0.0)
        assertTrue(v.isEmpty(), "实际：$v")
    }

    @Test
    fun `空配方合法`() {
        assertTrue(engine.isValid(recipe()))
    }

    @Test
    fun `autoFix-TooManyWanted移到可选`() {
        val r = recipe(wanted = setOf(1, 7, 21, 44, 51))
        val fixed = engine.autoFix(r, Violation.TooManyWanted(5, r.wantedFactorIds))
        assertEquals(4, fixed.wantedFactorIds.size)
        assertEquals(1, fixed.optionalFactorIds.size)
    }

    @Test
    fun `autoFix-重叠时保留想要`() {
        val r = recipe(wanted = setOf(1, 51), unwanted = setOf(51, 2))
        val fixed = engine.autoFix(r, Violation.WantedUnwantedOverlap(setOf(51)))
        assertTrue(51 in fixed.wantedFactorIds)
        assertFalse(51 in fixed.unwantedFactorIds)
    }
}
