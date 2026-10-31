plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    // 公开接口直接使用 Recipe、FactorDatabase，所以用 api 传递给 app
    api(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 测试中解析 factors.json
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    // 测试直接读取 app 中的运行时资源，不再复制一份
    systemProperty("kaze.iconDir", rootDir.resolve("app/src/main/assets/factors/icons").absolutePath)
    systemProperty("kaze.factorsJson", rootDir.resolve("app/src/main/assets/factors.json").absolutePath)
}
