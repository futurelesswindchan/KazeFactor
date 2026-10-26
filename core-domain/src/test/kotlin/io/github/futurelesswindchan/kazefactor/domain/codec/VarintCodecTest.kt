package io.github.futurelesswindchan.kazefactor.domain.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * VarintCodec 测试
 */
class VarintCodecTest {
    
    @Test
    fun `编码单字节整数`() {
        // 0~127 应该占 1 字节
        assertTrue(byteArrayOf(0x00).contentEquals(VarintCodec.encodeInt(0)))
        assertTrue(byteArrayOf(0x01).contentEquals(VarintCodec.encodeInt(1)))
        assertTrue(byteArrayOf(0x7F).contentEquals(VarintCodec.encodeInt(127)))
    }
    
    @Test
    fun `编码双字节整数`() {
        // 128~16383 应该占 2 字节
        val result128 = VarintCodec.encodeInt(128)
        assertEquals(2, result128.size)
        assertEquals(0x80.toByte(), result128[0])  // 最高位为 1，表示后续还有字节
        assertEquals(0x01.toByte(), result128[1])
        
        val result300 = VarintCodec.encodeInt(300)
        assertEquals(2, result300.size)
    }
    
    @Test
    fun `编码三字节整数`() {
        // 16384 及以上应该占 3+ 字节
        val result = VarintCodec.encodeInt(16384)
        assertEquals(3, result.size)
    }
    
    @Test
    fun `编码负数应抛出异常`() {
        assertFailsWith<IllegalArgumentException> {
            VarintCodec.encodeInt(-1)
        }
    }
    
    @Test
    fun `解码单字节整数`() {
        val (value, bytesRead) = VarintCodec.decodeInt(byteArrayOf(0x00))
        assertEquals(0, value)
        assertEquals(1, bytesRead)
        
        val (value127, bytesRead127) = VarintCodec.decodeInt(byteArrayOf(0x7F))
        assertEquals(127, value127)
        assertEquals(1, bytesRead127)
    }
    
    @Test
    fun `解码双字节整数`() {
        val bytes = byteArrayOf(0x80.toByte(), 0x01)
        val (value, bytesRead) = VarintCodec.decodeInt(bytes)
        assertEquals(128, value)
        assertEquals(2, bytesRead)
    }
    
    @Test
    fun `解码三字节整数`() {
        val encoded = VarintCodec.encodeInt(16384)
        val (value, bytesRead) = VarintCodec.decodeInt(encoded)
        assertEquals(16384, value)
        assertEquals(3, bytesRead)
    }
    
    @Test
    fun `编解码往返一致性`() {
        val testValues = listOf(0, 1, 127, 128, 255, 256, 16383, 16384, 65535, 65536, 1000000)
        
        for (original in testValues) {
            val encoded = VarintCodec.encodeInt(original)
            val (decoded, _) = VarintCodec.decodeInt(encoded)
            assertEquals(original, decoded, "编解码不一致：$original")
        }
    }
    
    @Test
    fun `编码整数列表`() {
        val list = listOf(1, 2, 3)
        val encoded = VarintCodec.encodeIntList(list)
        
        // 第一个字节应该是列表长度（3）
        assertEquals(0x03.toByte(), encoded[0])
    }
    
    @Test
    fun `解码整数列表`() {
        val list = listOf(1, 2, 3)
        val encoded = VarintCodec.encodeIntList(list)
        val (decoded, bytesRead) = VarintCodec.decodeIntList(encoded)
        
        assertEquals(list, decoded)
        assertEquals(encoded.size, bytesRead)
    }
    
    @Test
    fun `编解码空列表`() {
        val emptyList = emptyList<Int>()
        val encoded = VarintCodec.encodeIntList(emptyList)
        val (decoded, _) = VarintCodec.decodeIntList(encoded)
        
        assertEquals(emptyList, decoded)
    }
    
    @Test
    fun `编解码大整数列表往返一致性`() {
        val list = listOf(1, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 128, 300, 16384)
        val encoded = VarintCodec.encodeIntList(list)
        val (decoded, _) = VarintCodec.decodeIntList(encoded)
        
        assertEquals(list, decoded)
    }
    
    @Test
    fun `解码不完整数据应抛出异常`() {
        // 一个字节，最高位为 1，表示后续还有字节，但实际没有
        val incompleteData = byteArrayOf(0x80.toByte())
        
        assertFailsWith<IllegalArgumentException> {
            VarintCodec.decodeInt(incompleteData)
        }
    }
    
    @Test
    fun `解码超长 varint 应抛出异常`() {
        // 构造一个超过 5 字节的 varint（Int 最多占 5 字节）
        val tooLongData = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x80.toByte()  // 6 字节
        )
        
        assertFailsWith<IllegalArgumentException> {
            VarintCodec.decodeInt(tooLongData)
        }
    }
}
