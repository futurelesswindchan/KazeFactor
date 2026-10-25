package io.github.futurelesswindchan.kazefactor.domain.rule

import io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.testutil.loadFactorDatabase
import kotlin.test.*

class ProbabilityCalculatorTest {

    private val db = loadFactorDatabase()
    private val calculator = ProbabilityCalculator(db)

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
    fun `空配方-概率为1`() {
        assertEquals(1.0, calculator.calculate(recipe()), 1e-9)
    }

    @Test
    fun `单个大良-概率在0和1之间`() {
        val p = calculator.calculate(recipe(wanted = setOf(51))) // 无限能量
        assertTrue(p > 0 && p < 1, "p=$p")
        println("只要无限能量：${"%.2f".format(p * 100)}%，期望 ${"%.1f".format(1 / p)} 次")
    }

    @Test
    fun `两个大良-无限能量加特卖日`() {
        val p = calculator.calculate(recipe(wanted = setOf(51, 52)))
        assertTrue(p > 0, "两个大良可以同时出现在第3、4位")
        println("无限能量+特卖日：${"%.3f".format(p * 100)}%，期望 ${"%.0f".format(1 / p)} 次")
    }

    @Test
    fun `增加不想要-概率不升反降`() {
        val p1 = calculator.calculate(recipe(wanted = setOf(51)))
        val p2 = calculator.calculate(recipe(wanted = setOf(51), unwanted = setOf(1, 2)))
        assertTrue(p2 < p1, "排除条件只会让命中更难：$p1 -> $p2")
    }

    @Test
    fun `可选至少1个-概率不低于更严格的条件`() {
        val otherMajorGood = db.getFactorsByCategory(FactorCategory.MAJOR_GOOD)
            .map { it.id }.filter { it != 51 }.toSet()
        val loose = calculator.calculate(recipe(wanted = setOf(51), optional = otherMajorGood, minOptional = 1))
        val strict = calculator.calculate(recipe(wanted = setOf(51, 52)))
        assertTrue(loose >= strict, "至少1个其他大良 应 ≥ 指定特卖日")
    }

    @Test
    fun `冲突因子-概率为0`() {
        // 无限能量(51) 与 能量衰弱(23) 冲突，类别上本可同时出现
        assertEquals(0.0, calculator.calculate(recipe(wanted = setOf(51, 23))), 0.0)
    }

    @Test
    fun `四个大良-不可能`() {
        val r = recipe(wanted = setOf(51, 52, 53, 54))
        assertEquals(0.0, calculator.calculate(r), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, calculator.expectedRefreshCount(r))
    }

    @Test
    fun `两个大恶-不可能`() {
        // 大恶只能在第1位
        assertEquals(0.0, calculator.calculate(recipe(wanted = setOf(1, 2))), 0.0)
    }

    @Test
    fun `wiki轶事-大恶中性大良大良不存在`() {
        // 双领主(5) + 攻速强化术(43) + 冷却减半(59) + 无限能量(51)
        assertEquals(0.0, calculator.calculate(recipe(wanted = setOf(5, 43, 59, 51))), 0.0)
    }
}
