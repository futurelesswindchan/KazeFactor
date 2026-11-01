package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 第 1 类：逐张答案测试。47 张实机截图的 4 个槽位结果必须与 `labels.csv` 完全一致。
 *
 * 所有截图跑完再统一报错，一次看到全部错误，方便调阈值。
 */
class ScreenshotAnswerTest {

    private val recognizer = FactorRecognizer(TestFixtures.newMatcher())

    @Test
    fun `答案文件覆盖全部47张截图`() {
        val labels = TestFixtures.labels
        assertEquals(47, labels.size, "labels.csv 行数")
        assertEquals(labels.size, labels.map { it.file }.toSet().size, "labels.csv 中有重复文件名")
        assertTrue(labels.count { row -> row.expected.all { it == SlotResult.Question } } == 7, "应有 7 张问号截图")
    }

    @Test
    fun `每张截图的4个槽位都与答案一致`() {
        val failures = mutableListOf<String>()
        for (label in TestFixtures.labels) {
            val actual = recognizer.recognize(TestFixtures.loadScreenshot(label.file)).slots
            for (i in 0 until ReferenceLayout.SLOT_COUNT) {
                if (actual[i] != label.expected[i]) {
                    failures += "${label.file} 第 ${i + 1} 槽：期望 ${TestFixtures.describe(label.expected[i])}，" +
                        "实际 ${TestFixtures.describe(actual[i])}"
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail("共 ${failures.size} 个槽位识别错误：\n" + failures.joinToString("\n"))
        }
    }
}
