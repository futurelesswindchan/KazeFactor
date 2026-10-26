package io.github.futurelesswindchan.kazefactor.domain.codec

/**
 * CRC8 校验和计算工具
 * 
 * 用于口令码完整性校验，防止复制粘贴时出错。
 * 
 * 算法：CRC-8/MAXIM（polynomial 0x31，初始值 0x00，无反转）
 * - 适合短数据校验（口令码 payload 通常不超过 100 字节）
 * - 能检测常见的单字符错误、相邻字符交换、短突发错误
 */
internal object CRC8Calculator {
    // CRC-8/MAXIM 多项式 0x31 (x^8 + x^5 + x^4 + 1)
    private const val POLYNOMIAL: Int = 0x31
    
    // 预计算的查找表（加速计算）
    private val lookupTable: IntArray = IntArray(256) { i ->
        var crc = i
        repeat(8) {
            crc = if ((crc and 0x80) != 0) {
                (crc shl 1) xor POLYNOMIAL
            } else {
                crc shl 1
            }
        }
        crc and 0xFF
    }
    
    /**
     * 计算字节数组的 CRC8 校验和
     * 
     * @param data 待校验的字节数组
     * @return CRC8 校验和（0~255）
     */
    fun calculate(data: ByteArray): Int {
        var crc = 0x00
        
        for (byte in data) {
            val index = (crc xor (byte.toInt() and 0xFF)) and 0xFF
            crc = lookupTable[index]
        }
        
        return crc
    }
    
    /**
     * 验证数据与校验和是否匹配
     * 
     * @param data 待验证的字节数组
     * @param expectedChecksum 预期的 CRC8 校验和
     * @return 如果匹配返回 true
     */
    fun verify(data: ByteArray, expectedChecksum: Int): Boolean {
        return calculate(data) == expectedChecksum
    }
}
