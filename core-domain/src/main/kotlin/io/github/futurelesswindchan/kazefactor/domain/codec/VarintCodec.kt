package io.github.futurelesswindchan.kazefactor.domain.codec

import java.io.ByteArrayOutputStream

/**
 * Varint（变长整数）编解码工具
 * 
 * 用于压缩小整数，节省口令码空间：
 * - 0~127 占 1 字节
 * - 128~16383 占 2 字节
 * - 16384~2097151 占 3 字节
 * 
 * 编码规则：每字节最高位为标志位（1 表示后续还有字节，0 表示结束），
 * 剩余 7 位存储数据（小端序）
 */
internal object VarintCodec {
    /**
     * 编码单个非负整数为 varint 字节序列
     * 
     * @param value 非负整数（0 ~ Int.MAX_VALUE）
     * @return varint 字节数组
     * @throws IllegalArgumentException 如果 value 为负数
     */
    fun encodeInt(value: Int): ByteArray {
        require(value >= 0) { "Varint 只支持非负整数，收到：$value" }
        
        val output = ByteArrayOutputStream()
        var remaining = value
        
        while (remaining > 0x7F) {
            // 取低 7 位，最高位设为 1 表示后续还有字节
            output.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        
        // 最后一个字节，最高位为 0
        output.write(remaining and 0x7F)
        
        return output.toByteArray()
    }
    
    /**
     * 编码整数列表为 varint 字节序列
     * 
     * 格式：[count][value1][value2]...[valueN]
     * 
     * @param values 整数列表
     * @return varint 字节数组
     */
    fun encodeIntList(values: List<Int>): ByteArray {
        val output = ByteArrayOutputStream()
        
        // 先编码列表长度
        output.write(encodeInt(values.size))
        
        // 逐个编码元素
        for (value in values) {
            output.write(encodeInt(value))
        }
        
        return output.toByteArray()
    }
    
    /**
     * 从字节数组解码单个 varint 整数
     * 
     * @param bytes 字节数组
     * @param offset 起始偏移量
     * @return Pair(解码出的整数, 消耗的字节数)
     * @throws IllegalArgumentException 如果格式非法（超出 5 字节或提前结束）
     */
    fun decodeInt(bytes: ByteArray, offset: Int = 0): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var bytesRead = 0
        
        while (offset + bytesRead < bytes.size) {
            val byte = bytes[offset + bytesRead].toInt() and 0xFF
            bytesRead++
            
            // 取低 7 位拼接到结果中
            result = result or ((byte and 0x7F) shl shift)
            
            // 如果最高位为 0，表示结束
            if ((byte and 0x80) == 0) {
                return Pair(result, bytesRead)
            }
            
            shift += 7
            
            // 防止恶意构造的超长 varint（Int 最多占 5 字节）
            if (bytesRead > 5) {
                throw IllegalArgumentException("Varint 超出最大长度（5 字节）")
            }
        }
        
        throw IllegalArgumentException("Varint 数据不完整")
    }
    
    /**
     * 从字节数组解码整数列表
     * 
     * @param bytes 字节数组
     * @param offset 起始偏移量
     * @return Pair(解码出的整数列表, 消耗的字节数)
     */
    fun decodeIntList(bytes: ByteArray, offset: Int = 0): Pair<List<Int>, Int> {
        // 先解码列表长度
        val (count, countBytes) = decodeInt(bytes, offset)
        var currentOffset = offset + countBytes
        
        val result = mutableListOf<Int>()
        
        repeat(count) {
            val (value, valueBytes) = decodeInt(bytes, currentOffset)
            result.add(value)
            currentOffset += valueBytes
        }
        
        return Pair(result, currentOffset - offset)
    }
}
