package io.github.futurelesswindchan.kazefactor.domain.codec

import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.testutil.loadFactorDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RecipeCodec 测试
 */
class RecipeCodecTest {
    
    private val database = loadFactorDatabase()
    
    @Test
    fun `编码简单配方`() {
        val recipe = Recipe(
            id = "test-1",
            name = "简单配方",
            wantedFactorIds = setOf(51), // 无限能量
            unwantedFactorIds = emptySet(),
            optionalFactorIds = emptySet()
        )
        
        val result = RecipeCodec.encode(recipe, database)
        
        assertIs<RecipeCodec.EncodeResult.Success>(result)
        assertTrue(result.code.startsWith("SKF1-"))
        assertTrue(result.code.count { it == '-' } == 2) // 格式：SKF1-<base32>-<crc8>
    }
    
    @Test
    fun `编码包含所有字段的配方`() {
        val recipe = Recipe(
            id = "test-2",
            name = "完整配方",
            wantedFactorIds = setOf(51, 52), // 无限能量 + 特卖日
            unwantedFactorIds = setOf(1, 2, 3), // 大恶因子
            optionalFactorIds = setOf(44, 45, 46),
            minOptional = 2
        )
        
        val result = RecipeCodec.encode(recipe, database)
        
        assertIs<RecipeCodec.EncodeResult.Success>(result)
    }
    
    @Test
    fun `编码不存在的因子 ID 应失败`() {
        val recipe = Recipe(
            id = "test-invalid",
            name = "无效配方",
            wantedFactorIds = setOf(9999), // 不存在的 ID
            unwantedFactorIds = emptySet(),
            optionalFactorIds = emptySet()
        )
        
        val result = RecipeCodec.encode(recipe, database)
        
        assertIs<RecipeCodec.EncodeResult.Failure>(result)
        assertIs<RecipeCodec.EncodeError.InvalidFactorId>(result.error)
    }
    
    @Test
    fun `解码有效口令码`() {
        // 先编码一个配方
        val originalRecipe = Recipe(
            id = "test-3",
            name = "测试配方",
            wantedFactorIds = setOf(51),
            unwantedFactorIds = setOf(1, 2),
            optionalFactorIds = setOf(44, 45),
            minOptional = 1
        )
        
        val encodeResult = RecipeCodec.encode(originalRecipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        // 解码
        val decodeResult = RecipeCodec.decode(encodeResult.code, database)
        
        assertIs<RecipeCodec.DecodeResult.Success>(decodeResult)
        assertEquals(originalRecipe.wantedFactorIds, decodeResult.wantedFactorIds)
        assertEquals(originalRecipe.unwantedFactorIds, decodeResult.unwantedFactorIds)
        assertEquals(originalRecipe.optionalFactorIds, decodeResult.optionalFactorIds)
        assertEquals(originalRecipe.minOptional, decodeResult.minOptional)
    }
    
    @Test
    fun `解码格式错误的口令码`() {
        // 格式不对：缺少 CRC8
        val invalidCode1 = "SKF1-ABCDEFGH"
        val result1 = RecipeCodec.decode(invalidCode1, database)
        assertIs<RecipeCodec.DecodeResult.Failure>(result1)
        assertIs<RecipeCodec.DecodeError.InvalidFormat>(result1.error)
        
        // 格式不对：前缀错误
        val invalidCode2 = "KAZ1-ABCDEFGH-12"
        val result2 = RecipeCodec.decode(invalidCode2, database)
        assertIs<RecipeCodec.DecodeResult.Failure>(result2)
        assertIs<RecipeCodec.DecodeError.InvalidFormat>(result2.error)
        
        // 格式不对：CRC8 不是十六进制
        val invalidCode3 = "SKF1-ABCDEFGH-ZZ"
        val result3 = RecipeCodec.decode(invalidCode3, database)
        assertIs<RecipeCodec.DecodeResult.Failure>(result3)
        assertIs<RecipeCodec.DecodeError.InvalidFormat>(result3.error)
    }
    
    @Test
    fun `解码 CRC 校验失败的口令码`() {
        // 先编码一个有效配方
        val recipe = Recipe(
            id = "test-4",
            name = "测试配方",
            wantedFactorIds = setOf(51),
            unwantedFactorIds = emptySet(),
            optionalFactorIds = emptySet()
        )
        
        val encodeResult = RecipeCodec.encode(recipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        // 修改口令码，破坏 CRC
        val parts = encodeResult.code.split("-")
        val corruptedCode = "${parts[0]}-${parts[1]}-FF" // 随意改个 CRC
        
        val decodeResult = RecipeCodec.decode(corruptedCode, database)
        
        assertIs<RecipeCodec.DecodeResult.Failure>(decodeResult)
        assertIs<RecipeCodec.DecodeError.InvalidChecksum>(decodeResult.error)
    }
    
    @Test
    fun `解码包含未知因子 ID 的口令码`() {
        // 这个测试需要模拟一个「未来版本」的口令码
        // 方法：先用一个包含有效 ID 的配方编码，然后手动构造一个包含"不存在 ID"的 payload
        
        // 为了测试方便，咱们直接构造一个会被数据库拒绝的场景：
        // 假设数据库只有 ID 1~59，我们在解码时伪造一个包含 ID 9999 的 payload
        
        // 实际测试中，由于咱们的编码器会先验证 ID，所以无法直接编码出包含无效 ID 的口令码
        // 这里我们只能测试「如果收到了这样的口令码，解码器能否正确识别」
        
        // TODO: 需要手动构造包含无效 ID 的 payload 来测试 UnknownFactorId 错误
        // 暂时跳过这个测试，因为需要直接操作 payload 字节数组
    }
    
    @Test
    fun `编解码往返一致性 - 简单配方`() {
        val recipe = Recipe(
            id = "test-5",
            name = "往返测试",
            wantedFactorIds = setOf(51),
            unwantedFactorIds = emptySet(),
            optionalFactorIds = emptySet()
        )
        
        val encodeResult = RecipeCodec.encode(recipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        val decodeResult = RecipeCodec.decode(encodeResult.code, database)
        assertIs<RecipeCodec.DecodeResult.Success>(decodeResult)
        
        assertEquals(recipe.wantedFactorIds, decodeResult.wantedFactorIds)
        assertEquals(recipe.unwantedFactorIds, decodeResult.unwantedFactorIds)
        assertEquals(recipe.optionalFactorIds, decodeResult.optionalFactorIds)
        assertEquals(recipe.minOptional, decodeResult.minOptional)
    }
    
    @Test
    fun `编解码往返一致性 - 复杂配方`() {
        val recipe = Recipe(
            id = "test-6",
            name = "复杂往返测试",
            wantedFactorIds = setOf(51, 52, 53),
            unwantedFactorIds = setOf(1, 2, 3, 4, 5, 6),
            optionalFactorIds = setOf(44, 45, 46, 47, 48, 49, 50),
            minOptional = 3
        )
        
        val encodeResult = RecipeCodec.encode(recipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        val decodeResult = RecipeCodec.decode(encodeResult.code, database)
        assertIs<RecipeCodec.DecodeResult.Success>(decodeResult)
        
        assertEquals(recipe.wantedFactorIds, decodeResult.wantedFactorIds)
        assertEquals(recipe.unwantedFactorIds, decodeResult.unwantedFactorIds)
        assertEquals(recipe.optionalFactorIds, decodeResult.optionalFactorIds)
        assertEquals(recipe.minOptional, decodeResult.minOptional)
    }
    
    @Test
    fun `编解码往返一致性 - 只有 wanted`() {
        val recipe = Recipe(
            id = "test-7",
            name = "只要想要的",
            wantedFactorIds = setOf(51, 52),
            unwantedFactorIds = emptySet(),
            optionalFactorIds = emptySet()
        )
        
        val encodeResult = RecipeCodec.encode(recipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        val decodeResult = RecipeCodec.decode(encodeResult.code, database)
        assertIs<RecipeCodec.DecodeResult.Success>(decodeResult)
        
        assertEquals(recipe.wantedFactorIds, decodeResult.wantedFactorIds)
        assertEquals(recipe.unwantedFactorIds, decodeResult.unwantedFactorIds)
        assertEquals(recipe.optionalFactorIds, decodeResult.optionalFactorIds)
    }
    
    @Test
    fun `编解码往返一致性 - 只有 unwanted`() {
        val recipe = Recipe(
            id = "test-8",
            name = "只排除不想要的",
            wantedFactorIds = emptySet(),
            unwantedFactorIds = setOf(1, 2, 3, 4),
            optionalFactorIds = emptySet()
        )
        
        val encodeResult = RecipeCodec.encode(recipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        val decodeResult = RecipeCodec.decode(encodeResult.code, database)
        assertIs<RecipeCodec.DecodeResult.Success>(decodeResult)
        
        assertEquals(recipe.wantedFactorIds, decodeResult.wantedFactorIds)
        assertEquals(recipe.unwantedFactorIds, decodeResult.unwantedFactorIds)
        assertEquals(recipe.optionalFactorIds, decodeResult.optionalFactorIds)
    }
    
    @Test
    fun `口令码不含易混淆字符`() {
        val recipe = Recipe(
            id = "test-9",
            name = "易读性测试",
            wantedFactorIds = setOf(51, 52, 53, 54, 55),
            unwantedFactorIds = setOf(1, 2, 3),
            optionalFactorIds = setOf(44, 45, 46),
            minOptional = 2
        )
        
        val encodeResult = RecipeCodec.encode(recipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        val code = encodeResult.code
        
        // 检查 Base32 部分（第二段）不包含易混淆字符
        val base32Part = code.split("-")[1]
        
        assertFalse(base32Part.contains('0'), "口令码不应包含 0（零）")
        assertFalse(base32Part.contains('O'), "口令码不应包含 O（大写字母O）")
        assertFalse(base32Part.contains('1'), "口令码不应包含 1（一）")
        assertFalse(base32Part.contains('I'), "口令码不应包含 I（大写字母I）")
        assertFalse(base32Part.contains('l'), "口令码不应包含 l（小写字母L）")
    }
    
    @Test
    fun `口令码长度合理`() {
        val recipe = Recipe(
            id = "test-10",
            name = "长度测试",
            wantedFactorIds = setOf(51),
            unwantedFactorIds = emptySet(),
            optionalFactorIds = emptySet()
        )
        
        val encodeResult = RecipeCodec.encode(recipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        val code = encodeResult.code
        
        // 口令码长度应该是合理的（不要太长，适合复制粘贴）
        // 格式：SKF1-<base32>-<2位16进制>
        assertTrue(code.length < 200, "口令码长度应小于 200 字符，实际：${code.length}")
        
        // 检查格式
        val parts = code.split("-")
        assertEquals(3, parts.size)
        assertEquals("SKF1", parts[0])
        assertEquals(2, parts[2].length) // CRC8 是 2 位 16 进制
    }
    
    @Test
    fun `解码大小写不敏感`() {
        val recipe = Recipe(
            id = "test-11",
            name = "大小写测试",
            wantedFactorIds = setOf(51),
            unwantedFactorIds = emptySet(),
            optionalFactorIds = emptySet()
        )
        
        val encodeResult = RecipeCodec.encode(recipe, database)
        assertIs<RecipeCodec.EncodeResult.Success>(encodeResult)
        
        val originalCode = encodeResult.code
        
        // 转小写解码
        val lowerResult = RecipeCodec.decode(originalCode.lowercase(), database)
        assertIs<RecipeCodec.DecodeResult.Success>(lowerResult)
        
        // 转大写解码
        val upperResult = RecipeCodec.decode(originalCode.uppercase(), database)
        assertIs<RecipeCodec.DecodeResult.Success>(upperResult)
        
        // 结果应该一致
        assertEquals(recipe.wantedFactorIds, lowerResult.wantedFactorIds)
        assertEquals(recipe.wantedFactorIds, upperResult.wantedFactorIds)
    }
    
    private fun assertFalse(condition: Boolean, message: String = "") {
        assertTrue(!condition, message)
    }
}
