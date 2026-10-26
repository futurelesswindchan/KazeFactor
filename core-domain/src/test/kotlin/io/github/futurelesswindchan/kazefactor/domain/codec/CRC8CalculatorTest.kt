package io.github.futurelesswindchan.kazefactor.domain.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CRC8Calculator 测试
 */
class CRC8CalculatorTest {
    
    @Test
    fun `空数据的 CRC8 应为 0`() {
        val crc = CRC8Calculator.calculate(ByteArray(0))
        assertEquals(0, crc)
    }
    
    @Test
    fun `单字节数据的 CRC8`() {
        val crc = CRC8Calculator.calculate(byteArrayOf(0x00))
        assertEquals(0, crc)  // 输入 0x00，CRC 应为 0
        
        val crc1 = CRC8Calculator.calculate(byteArrayOf(0x01))
        assertTrue(crc1 != 0)  // 输入非零，CRC 不应为 0
    }
    
    @Test
    fun `多字节数据的 CRC8`() {
        val data = "Hello".toByteArray(Charsets.UTF_8)
        val crc = CRC8Calculator.calculate(data)
        
        // CRC8 结果应在 0~255 范围内
        assertTrue(crc in 0..255)
    }
    
    @Test
    fun `相同数据计算结果一致`() {
        val data = "KazeFactor".toByteArray(Charsets.UTF_8)
        
        val crc1 = CRC8Calculator.calculate(data)
        val crc2 = CRC8Calculator.calculate(data)
        
        assertEquals(crc1, crc2)
    }
    
    @Test
    fun `不同数据计算结果不同`() {
        val data1 = "Hello".toByteArray(Charsets.UTF_8)
        val data2 = "World".toByteArray(Charsets.UTF_8)
        
        val crc1 = CRC8Calculator.calculate(data1)
        val crc2 = CRC8Calculator.calculate(data2)
        
        // 极小概率碰撞，但一般不应相同
        assertTrue(crc1 != crc2)
    }
    
    @Test
    fun `数据顺序敏感`() {
        val data1 = byteArrayOf(0x01, 0x02, 0x03)
        val data2 = byteArrayOf(0x03, 0x02, 0x01)
        
        val crc1 = CRC8Calculator.calculate(data1)
        val crc2 = CRC8Calculator.calculate(data2)
        
        assertTrue(crc1 != crc2)
    }
    
    @Test
    fun `校验正确数据应返回 true`() {
        val data = "KazeFactor".toByteArray(Charsets.UTF_8)
        val crc = CRC8Calculator.calculate(data)
        
        assertTrue(CRC8Calculator.verify(data, crc))
    }
    
    @Test
    fun `校验错误数据应返回 false`() {
        val data = "KazeFactor".toByteArray(Charsets.UTF_8)
        val crc = CRC8Calculator.calculate(data)
        
        // 修改数据的第一个字节
        data[0] = (data[0] + 1).toByte()
        
        assertFalse(CRC8Calculator.verify(data, crc))
    }
    
    @Test
    fun `校验错误校验和应返回 false`() {
        val data = "KazeFactor".toByteArray(Charsets.UTF_8)
        val crc = CRC8Calculator.calculate(data)
        
        // 修改校验和
        val wrongCrc = (crc + 1) and 0xFF
        
        assertFalse(CRC8Calculator.verify(data, wrongCrc))
    }
    
    @Test
    fun `能检测单字符错误`() {
        val original = "SKF1-ABCDEFGH-12".toByteArray(Charsets.UTF_8)
        val crc = CRC8Calculator.calculate(original)
        
        // 复制数据并修改一个字符
        val corrupted = original.copyOf()
        corrupted[5] = (corrupted[5] + 1).toByte()  // 修改 'A'
        
        assertFalse(CRC8Calculator.verify(corrupted, crc))
    }
}
