package io.github.futurelesswindchan.kazefactor.domain.codec

import java.io.ByteArrayOutputStream

/**
 * 自定义 Base32 编解码器
 * 
 * 去掉易混淆字符（0O1Il），适合复制粘贴场景（如 B 站评论）。
 * 
 * 字符集：23456789ABCDEFGHJKMNPQRSTUVWXYZ（32 个字符）
 * - 去除 0（零）和 O（大写字母O），避免混淆
 * - 去除 1（一）、I（大写字母I）、l（小写字母L），避免混淆
 * 
 * 编码规则：
 * - 每 5 字节（40 位）编码为 8 个 Base32 字符
 * - 不足 5 字节的末尾用 0 补齐，编码后去掉对应的填充字符
 */
internal object Base32Encoder {
    // 自定义字符集（去掉 0O1Il）
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    
    // 字符到索引的映射表（用于解码）
    private val decodeMap: Map<Char, Int> = ALPHABET.mapIndexed { index, char -> char to index }.toMap()
    
    /**
     * 编码字节数组为 Base32 字符串
     * 
     * @param data 待编码的字节数组
     * @return Base32 字符串（大写，无填充符）
     */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        
        val result = StringBuilder()
        var buffer = 0L  // 用 Long 存储当前缓冲区（最多 40 位）
        var bitsInBuffer = 0
        
        for (byte in data) {
            // 将字节追加到缓冲区
            buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
            bitsInBuffer += 8
            
            // 每次凑够 5 位就输出一个 Base32 字符
            while (bitsInBuffer >= 5) {
                val index = ((buffer shr (bitsInBuffer - 5)) and 0x1F).toInt()
                result.append(ALPHABET[index])
                bitsInBuffer -= 5
            }
        }
        
        // 处理剩余不足 5 位的部分
        if (bitsInBuffer > 0) {
            val index = ((buffer shl (5 - bitsInBuffer)) and 0x1F).toInt()
            result.append(ALPHABET[index])
        }
        
        return result.toString()
    }
    
    /**
     * 解码 Base32 字符串为字节数组
     * 
     * @param encoded Base32 字符串（大小写不敏感）
     * @return 解码后的字节数组
     * @throws IllegalArgumentException 如果字符串包含非法字符
     */
    fun decode(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)
        
        val upperEncoded = encoded.uppercase()
        val output = ByteArrayOutputStream()
        var buffer = 0L
        var bitsInBuffer = 0
        
        for (char in upperEncoded) {
            val value = decodeMap[char]
                ?: throw IllegalArgumentException("Base32 字符串包含非法字符：$char")
            
            // 将 5 位追加到缓冲区
            buffer = (buffer shl 5) or value.toLong()
            bitsInBuffer += 5
            
            // 每次凑够 8 位就输出一个字节
            while (bitsInBuffer >= 8) {
                val byte = ((buffer shr (bitsInBuffer - 8)) and 0xFF).toInt()
                output.write(byte)
                bitsInBuffer -= 8
            }
        }
        
        // 注意：剩余不足 8 位的部分是编码时的填充，直接丢弃
        
        return output.toByteArray()
    }
}
