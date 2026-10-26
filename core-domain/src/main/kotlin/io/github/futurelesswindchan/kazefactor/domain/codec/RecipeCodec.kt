package io.github.futurelesswindchan.kazefactor.domain.codec

import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import java.io.ByteArrayOutputStream

/**
 * 配方口令码编解码器
 * 
 * 口令码格式：SKF1-<Base32(payload)>-<CRC8>
 * 
 * payload 结构：
 * - dataVersion (varint): 数据版本号，用于兼容性检查
 * - flags (varint): 预留扩展位（目前为 0）
 * - wanted ids (varint list): 想要的因子 ID 列表
 * - unwanted ids (varint list): 不想要的因子 ID 列表
 * - optional ids (varint list): 可选因子 ID 列表
 * - minOptional (varint): 可选因子的最小出现数量
 * 
 * 注意：
 * - 配方名不进口令码，导入时由用户命名
 * - 不认识的因子 ID → 提示"请更新 App"
 * - CRC 校验失败 → 提示"粘贴有误，请检查口令码"
 */
object RecipeCodec {
    private const val PREFIX = "SKF1"
    private const val SEPARATOR = "-"
    
    /**
     * 编码结果
     */
    sealed class EncodeResult {
        data class Success(val code: String) : EncodeResult()
        data class Failure(val error: EncodeError) : EncodeResult()
    }
    
    /**
     * 解码结果
     */
    sealed class DecodeResult {
        data class Success(
            val dataVersion: Int,
            val wantedFactorIds: Set<Int>,
            val unwantedFactorIds: Set<Int>,
            val optionalFactorIds: Set<Int>,
            val minOptional: Int
        ) : DecodeResult()
        
        data class Failure(val error: DecodeError) : DecodeResult()
    }
    
    /**
     * 编码错误
     */
    sealed class EncodeError {
        data class InvalidFactorId(val factorId: Int) : EncodeError() {
            override fun toString() = "因子 ID $factorId 不存在于数据库中"
        }
    }
    
    /**
     * 解码错误
     */
    sealed class DecodeError {
        object InvalidFormat : DecodeError() {
            override fun toString() = "口令码格式错误，请检查是否完整复制"
        }
        
        object InvalidChecksum : DecodeError() {
            override fun toString() = "口令码校验失败，可能在复制时出错，请重新复制"
        }
        
        data class UnknownFactorId(val factorIds: Set<Int>) : DecodeError() {
            override fun toString() = "口令码包含未知因子 ID：${factorIds.joinToString()}，请更新 App"
        }
        
        object InvalidPayload : DecodeError() {
            override fun toString() = "口令码数据解析失败"
        }
    }
    
    /**
     * 将配方编码为口令码
     * 
     * @param recipe 待编码的配方
     * @param database 因子数据库（用于验证 ID 是否有效）
     * @return 编码结果
     */
    fun encode(recipe: Recipe, database: FactorDatabase): EncodeResult {
        // 验证所有因子 ID 是否存在于数据库中
        val allFactorIds = recipe.getAllFactorIds()
        val validFactorIds = database.getAllFactorIds()
        
        val invalidIds = allFactorIds.filter { it !in validFactorIds }
        if (invalidIds.isNotEmpty()) {
            return EncodeResult.Failure(EncodeError.InvalidFactorId(invalidIds.first()))
        }
        
        // 构建 payload
        val payload = buildPayload(
            dataVersion = database.dataVersion,
            wantedFactorIds = recipe.wantedFactorIds.sorted(),
            unwantedFactorIds = recipe.unwantedFactorIds.sorted(),
            optionalFactorIds = recipe.optionalFactorIds.sorted(),
            minOptional = recipe.minOptional
        )
        
        // 计算 CRC8 校验和
        val checksum = CRC8Calculator.calculate(payload)
        
        // Base32 编码
        val base32Payload = Base32Encoder.encode(payload)
        
        // 拼接口令码
        val code = "$PREFIX$SEPARATOR$base32Payload$SEPARATOR${checksum.toString(16).uppercase().padStart(2, '0')}"
        
        return EncodeResult.Success(code)
    }
    
    /**
     * 解码口令码为配方数据
     * 
     * @param code 口令码字符串
     * @param database 因子数据库（用于验证 ID 是否有效）
     * @return 解码结果
     */
    fun decode(code: String, database: FactorDatabase): DecodeResult {
        // 校验格式：SKF1-<base32>-<crc8>
        val parts = code.split(SEPARATOR)
        if (parts.size != 3 || !parts[0].equals(PREFIX, ignoreCase = true)) {
            return DecodeResult.Failure(DecodeError.InvalidFormat)
        }
        
        val base32Payload = parts[1]
        val crc8Hex = parts[2]
        
        // 解析 CRC8
        val expectedChecksum = try {
            crc8Hex.toInt(16)
        } catch (e: NumberFormatException) {
            return DecodeResult.Failure(DecodeError.InvalidFormat)
        }
        
        // Base32 解码
        val payload = try {
            Base32Encoder.decode(base32Payload)
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Failure(DecodeError.InvalidFormat)
        }
        
        // 校验 CRC8
        if (!CRC8Calculator.verify(payload, expectedChecksum)) {
            return DecodeResult.Failure(DecodeError.InvalidChecksum)
        }
        
        // 解析 payload
        return try {
            parsePayload(payload, database)
        } catch (e: Exception) {
            DecodeResult.Failure(DecodeError.InvalidPayload)
        }
    }
    
    /**
     * 构建 payload 字节数组
     */
    private fun buildPayload(
        dataVersion: Int,
        wantedFactorIds: List<Int>,
        unwantedFactorIds: List<Int>,
        optionalFactorIds: List<Int>,
        minOptional: Int
    ): ByteArray {
        val output = ByteArrayOutputStream()
        
        // dataVersion
        output.write(VarintCodec.encodeInt(dataVersion))
        
        // flags（预留，目前为 0）
        output.write(VarintCodec.encodeInt(0))
        
        // wanted ids
        output.write(VarintCodec.encodeIntList(wantedFactorIds))
        
        // unwanted ids
        output.write(VarintCodec.encodeIntList(unwantedFactorIds))
        
        // optional ids
        output.write(VarintCodec.encodeIntList(optionalFactorIds))
        
        // minOptional
        output.write(VarintCodec.encodeInt(minOptional))
        
        return output.toByteArray()
    }
    
    /**
     * 解析 payload 字节数组
     */
    private fun parsePayload(payload: ByteArray, database: FactorDatabase): DecodeResult {
        var offset = 0
        
        // dataVersion
        val (dataVersion, dataVersionBytes) = VarintCodec.decodeInt(payload, offset)
        offset += dataVersionBytes
        
        // flags（预留，目前忽略）
        val (_, flagsBytes) = VarintCodec.decodeInt(payload, offset)
        offset += flagsBytes
        
        // wanted ids
        val (wantedList, wantedBytes) = VarintCodec.decodeIntList(payload, offset)
        offset += wantedBytes
        
        // unwanted ids
        val (unwantedList, unwantedBytes) = VarintCodec.decodeIntList(payload, offset)
        offset += unwantedBytes
        
        // optional ids
        val (optionalList, optionalBytes) = VarintCodec.decodeIntList(payload, offset)
        offset += optionalBytes
        
        // minOptional
        val (minOptional, _) = VarintCodec.decodeInt(payload, offset)
        
        // 验证所有因子 ID 是否存在于数据库中
        val allIds = (wantedList + unwantedList + optionalList).toSet()
        val validIds = database.getAllFactorIds()
        val unknownIds = allIds.filter { it !in validIds }.toSet()
        
        if (unknownIds.isNotEmpty()) {
            return DecodeResult.Failure(DecodeError.UnknownFactorId(unknownIds))
        }
        
        return DecodeResult.Success(
            dataVersion = dataVersion,
            wantedFactorIds = wantedList.toSet(),
            unwantedFactorIds = unwantedList.toSet(),
            optionalFactorIds = optionalList.toSet(),
            minOptional = minOptional
        )
    }
}
