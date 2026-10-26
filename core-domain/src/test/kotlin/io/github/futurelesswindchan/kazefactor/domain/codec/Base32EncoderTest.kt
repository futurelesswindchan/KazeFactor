package io.github.futurelesswindchan.kazefactor.domain.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Base32Encoder 测试
 */
class Base32EncoderTest {
    
    @Test
    fun `编码空数据`() {
        val encoded = Base32Encoder.encode(ByteArray(0))
        assertEquals("", encoded)
    }
    
    @Test
    fun `编码单字节数据`() {
        val data = byteArrayOf(0x00)
        val encoded = Base32Encoder.encode(data)
        
        // 结果应该是纯大写字母+数字，且不包含 0O1Il
        assertTrue(encoded.matches(Regex("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]+")))
    }
    
    @Test
    fun `编码多字节数据`() {
        val data = "Hello".toByteArray(Charsets.UTF_8)
        val encoded = Base32Encoder.encode(data)
        
        // 结果不应包含易混淆字符
        assertFalse(encoded.contains('0'))
        assertFalse(encoded.contains('O'))
        assertFalse(encoded.contains('1'))
        assertFalse(encoded.contains('I'))
        assertFalse(encoded.contains('l'))
    }
    
    @Test
    fun `解码空字符串`() {
        val decoded = Base32Encoder.decode("")
        assertEquals(0, decoded.size)
    }
    
    @Test
    fun `编解码往返一致性`() {
        val testData = listOf(
            byteArrayOf(0x00),
            byteArrayOf(0x01, 0x02, 0x03),
            "Hello World".toByteArray(Charsets.UTF_8),
            "元气骑士".toByteArray(Charsets.UTF_8),
            ByteArray(100) { it.toByte() }  // 0~99 的字节序列
        )
        
        for (original in testData) {
            val encoded = Base32Encoder.encode(original)
            val decoded = Base32Encoder.decode(encoded)
            
            assertTrue(original.contentEquals(decoded), "编解码不一致")
        }
    }
    
    @Test
    fun `解码大小写不敏感`() {
        val data = "KazeFactor".toByteArray(Charsets.UTF_8)
        val encoded = Base32Encoder.encode(data)
        
        val decodedUpper = Base32Encoder.decode(encoded.uppercase())
        val decodedLower = Base32Encoder.decode(encoded.lowercase())
        
        assertTrue(data.contentEquals(decodedUpper))
        assertTrue(data.contentEquals(decodedLower))
    }
    
    @Test
    fun `解码非法字符应抛出异常`() {
        // 包含易混淆字符 0（零）
        assertFailsWith<IllegalArgumentException> {
            Base32Encoder.decode("ABC0DEF")
        }
        
        // 包含易混淆字符 O（大写字母O）
        assertFailsWith<IllegalArgumentException> {
            Base32Encoder.decode("ABCODEF")
        }
        
        // 包含易混淆字符 1（一）
        assertFailsWith<IllegalArgumentException> {
            Base32Encoder.decode("ABC1DEF")
        }
        
        // 包含易混淆字符 I（大写字母I）
        assertFailsWith<IllegalArgumentException> {
            Base32Encoder.decode("ABCIDEF")
        }
    }
    
    @Test
    fun `编码结果不含填充符`() {
        val data = "Test".toByteArray(Charsets.UTF_8)
        val encoded = Base32Encoder.encode(data)
        
        // 我们的实现不使用填充符（=）
        assertFalse(encoded.contains('='))
    }
    
    @Test
    fun `编码各种长度数据`() {
        // 测试不同长度数据的编解码
        for (length in 1..20) {
            val data = ByteArray(length) { it.toByte() }
            val encoded = Base32Encoder.encode(data)
            val decoded = Base32Encoder.decode(encoded)
            
            assertTrue(data.contentEquals(decoded), "长度 $length 编解码失败")
        }
    }
    
    @Test
    fun `编码结果可复制粘贴`() {
        // 模拟常见口令码场景：包含因子 ID 列表的数据
        val data = byteArrayOf(1, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59)
        val encoded = Base32Encoder.encode(data)
        
        // 结果应该是纯字母数字，适合复制粘贴
        assertTrue(encoded.matches(Regex("[A-Z0-9]+")))
        
        // 不包含易混淆字符
        assertFalse("0OIl1".any { it in encoded })
    }
    
    private fun assertFalse(condition: Boolean, message: String = "") {
        assertTrue(!condition, message)
    }
}
