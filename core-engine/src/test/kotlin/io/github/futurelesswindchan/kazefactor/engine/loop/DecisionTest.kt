package io.github.futurelesswindchan.kazefactor.engine.loop

import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [Decision] 的单元测试。
 *
 * 测试方针：只验证判定逻辑，不涉及截图和识别。用 `TestFixtures.database` 查因子。
 * 因子 key 来自 `factors.json`，类别组合对应 17 种合法模式之一。
 */
class DecisionTest {

    private val db = TestFixtures.database
    private val decision = Decision(db)

    // ---- 辅助 ----

    private fun id(key: String): Int = db.getFactorByKey(key)?.id ?: error("未知因子 key: $key")

    private fun factor(key: String) = SlotResult.Factor(id(key))
    private fun ambiguous(vararg keys: String) = SlotResult.Ambiguous(keys.map { id(it) }.toSet())

    /** 空配方：什么都不想要、什么都不排除，任何结果都命中。 */
    private val emptyRecipe = Recipe(
        id = "test-empty",
        name = "空配方",
        wantedFactorIds = emptySet()
    )

    // ---- Unknown / Question → UNCERTAIN ----

    @Test
    fun `有 Unknown 槽位返回 UNCERTAIN`() {
        val slots = listOf(
            factor("double_joy"),       // 大恶
            SlotResult.Unknown,
            factor("infinite_energy"),  // 大良
            factor("gigantic_pet")      // 大良
        )
        val r = decision.decide(slots, emptyRecipe)
        assertIs<DecisionResult.Uncertain>(r)
        assertEquals(UncertainReason.UNKNOWN_SLOT, r.reason)
    }

    @Test
    fun `有 Question 槽位返回 UNCERTAIN`() {
        val slots = listOf(
            factor("double_joy"),
            factor("weapon_overheating"), // 中恶
            SlotResult.Question,
            factor("gigantic_pet")
        )
        val r = decision.decide(slots, emptyRecipe)
        assertIs<DecisionResult.Uncertain>(r)
        assertEquals(UncertainReason.QUESTION_SLOT, r.reason)
    }

    // ---- 全部确定 → HIT / MISS ----
    // PATTERN_3: 大恶 + 中恶 + 大良 + 大良

    @Test
    fun `全部确定且命中`() {
        val energyId = id("infinite_energy")
        val slots = listOf(
            factor("double_joy"),         // 大恶 → 第 1 槽
            factor("weapon_overheating"), // 中恶 → 第 2 槽
            factor("infinite_energy"),    // 大良 → 第 3 槽
            factor("gigantic_pet")        // 大良 → 第 4 槽
        )
        val recipe = Recipe(id = "test", name = "T", wantedFactorIds = setOf(energyId))
        val r = decision.decide(slots, recipe)
        assertIs<DecisionResult.Hit>(r)
        assertEquals(4, r.factorIds.size)
        assertTrue(energyId in r.factorIds)
    }

    @Test
    fun `全部确定但不命中`() {
        val energyId = id("infinite_energy")
        // 第 3 槽放 sales_day 而不是 infinite_energy
        val slots = listOf(
            factor("double_joy"),
            factor("weapon_overheating"),
            factor("sales_day"),      // 大良，但不是 infinite_energy
            factor("gigantic_pet")
        )
        val recipe = Recipe(id = "test", name = "T", wantedFactorIds = setOf(energyId))
        val r = decision.decide(slots, recipe)
        assertIs<DecisionResult.Miss>(r)
    }

    // ---- unwanted 配方 ----

    @Test
    fun `排除的因子出现返回 MISS`() {
        val excluded = id("double_joy")
        val slots = listOf(
            factor("double_joy"),
            factor("weapon_overheating"),
            factor("infinite_energy"),
            factor("gigantic_pet")
        )
        val recipe = Recipe(id = "test", name = "T", wantedFactorIds = emptySet(), unwantedFactorIds = setOf(excluded))
        val r = decision.decide(slots, recipe)
        assertIs<DecisionResult.Miss>(r)
    }

    @Test
    fun `排除的因子没出现返回 HIT`() {
        val excluded = id("cell_division") // 大恶，但不在本组合里
        val slots = listOf(
            factor("double_joy"),
            factor("weapon_overheating"),
            factor("infinite_energy"),
            factor("gigantic_pet")
        )
        val recipe = Recipe(id = "test", name = "T", wantedFactorIds = emptySet(), unwantedFactorIds = setOf(excluded))
        val r = decision.decide(slots, recipe)
        assertIs<DecisionResult.Hit>(r)
    }

    // ---- Ambiguous 展开 ----

    @Test
    fun `Ambiguous 展开后全部命中`() {
        // buff_choice_plus1(30) 和 buff_buffet(48) 都是在第 3 槽可能出现的因子
        // buff_choice_plus1 = NEUTRAL，buff_buffet = MINOR_GOOD
        // 要构造合法组合：对第 3 槽含 NEUTRAL 或 MINOR_GOOD 的都行
        // PATTERN_8: 大恶 + 中性 + 小良 + 大良 → buff_buffet 是小良，可出现在第 3 槽
        // PATTERN_4: 大恶 + 小恶 + 中性 + 大良 → buff_choice_plus1 是中性，可出现在第 3 槽
        // 两者类别不同，展开后的组合可能不属于同一个 pattern。
        // 简化：空配方，不管命中/不命中，只看是否 HIT
        val slots = listOf(
            factor("double_joy"),        // 大恶 → 第 1 槽
            factor("weapon_overheating"), // 中恶 → 第 2 槽
            ambiguous("buff_choice_plus1", "buff_buffet"),
            factor("gigantic_pet")        // 大良 → 第 4 槽
        )
        // buff_choice_plus1(NEUTRAL) → PATTERN_1: 大恶+中恶+中性+大良 ✓
        // buff_buffet(MINOR_GOOD) → 大恶+中恶+小良+大良 = PATTERN_2 ✓
        // 空配方，两个都命中 → HIT
        val r = decision.decide(slots, emptyRecipe)
        assertIs<DecisionResult.Hit>(r)
    }

    @Test
    fun `Ambiguous 展开后全部未命中`() {
        val energyId = id("infinite_energy")
        val slots = listOf(
            factor("double_joy"),
            factor("weapon_overheating"),
            ambiguous("buff_choice_plus1", "buff_buffet"),
            factor("gigantic_pet")
        )
        // 想要 infinite_energy，但第 3 槽候选都不是 → 两个组合都不命中
        val recipe = Recipe(id = "test", name = "T", wantedFactorIds = setOf(energyId))
        val r = decision.decide(slots, recipe)
        assertIs<DecisionResult.Miss>(r)
    }

    @Test
    fun `Ambiguous 展开后结论不一致返回 UNCERTAIN`() {
        // 想要 buff_choice_plus1，第 3 槽 Ambiguous 里有它也有 buff_buffet
        val wantedId = id("buff_choice_plus1")
        val slots = listOf(
            factor("double_joy"),
            factor("weapon_overheating"),
            ambiguous("buff_choice_plus1", "buff_buffet"),
            factor("gigantic_pet")
        )
        val recipe = Recipe(id = "test", name = "T", wantedFactorIds = setOf(wantedId))
        val r = decision.decide(slots, recipe)
        assertIs<DecisionResult.Uncertain>(r)
        assertEquals(UncertainReason.CONFLICTING_OUTCOMES, r.reason)
    }

    // ---- isValidCombination ----

    @Test
    fun `合法组合通过检查`() {
        // PATTERN_3: 大恶 + 中恶 + 大良 + 大良
        val ids = listOf(id("double_joy"), id("weapon_overheating"), id("infinite_energy"), id("gigantic_pet"))
        assertTrue(decision.isValidCombination(ids))
    }

    @Test
    fun `重复因子不合法`() {
        val e = id("infinite_energy")
        val ids = listOf(id("double_joy"), id("weapon_overheating"), e, e)
        assertTrue(!decision.isValidCombination(ids))
    }

    @Test
    fun `冲突因子不合法`() {
        // conflicts 里有 [3, 55] = fragility 与 regeneration
        // PATTERN_3: 大恶(fragility) + 中恶 + 大良(regeneration) + 大良
        val ids = listOf(id("fragility"), id("weapon_overheating"), id("regeneration"), id("gigantic_pet"))
        assertTrue(!decision.isValidCombination(ids))
    }
}
