package io.github.futurelesswindchan.kazefactor.domain.testutil

import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * 从测试资源加载真实的 factors.json（59 个因子 + 11 组冲突）
 * 结果缓存，避免每个测试重复解析
 */
private val cachedDb: FactorDatabase by lazy {
    val text = Thread.currentThread().contextClassLoader
        .getResource("factors.json")
        ?.readText()
        ?: error("无法读取测试资源 factors.json")
    json.decodeFromString<FactorDatabase>(text)
}

fun loadFactorDatabase(): FactorDatabase = cachedDb
