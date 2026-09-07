plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

group = "com.netonstream.app"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        val coreInterop =
            rootProject.file("../../Neton/neton/neton-core/build/nativeInterop/${target.name}").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
        }
    }

    sourceSets {
        commonMain {
            // FlatBuffers（MMO_ARCHITECTURE_SPEC §10.6）：
            //   - protocol/runtime：vendor 进来的 flatbuffers-kotlin 多平台运行时
            //     （google/flatbuffers kotlin/flatbuffers-kotlin，上游从未发布到 Maven，
            //     且只声明了 macOS/iOS target；平台相关代码只有 nativeMain 一个文件）。
            //     它只服务本模块，所以放在本模块而不是 neton 框架里。
            //   - protocol/generated/kotlin-kmp：`flatc --kotlin-kmp` 的产物，由
            //     protocol/scripts 重新生成，不手改。
            //   `flatc --kotlin`（JVM 后端）的旧产物留在 protocol/generated/kotlin，不挂载。
            kotlin.srcDir("protocol/runtime/commonMain")
            kotlin.srcDir("protocol/generated/kotlin-kmp")
            dependencies {
                implementation("com.netonstream.app:module-system")
                implementation("com.netonstream.app:module-infra")
                // 提供 PrivChatTransferServiceRegistry / PrivChatTransferHandler
                implementation("com.netonstream.privchat:main")
                implementation("com.netonstream:privchat-service-client")
                implementation("com.netonstream:neton-core")
                implementation("com.netonstream:neton-routing")
                implementation("com.netonstream:neton-security")
                implementation("com.netonstream:neton-http")
                implementation("com.netonstream:neton-database")
                implementation("com.netonstream:neton-logging")
                implementation("com.netonstream:neton-validation")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

dependencies {
    add("kspMacosArm64", "com.netonstream:neton-ksp")
    add("kspLinuxX64", "com.netonstream:neton-ksp")
    add("kspLinuxArm64", "com.netonstream:neton-ksp")
    add("kspMingwX64", "com.netonstream:neton-ksp")
}

ksp {
    arg("neton.moduleId", "mmorpg")
}

// KSP 输出只从 macosArm64 生成一份，挂到 commonMain 供所有 target 共享。
// 每 target 各生成一份会导致符号重定义（与 module-game / module-member 同形态）。
afterEvaluate {
    val kspOut = file("build/generated/ksp/macosArm64/macosArm64Main/kotlin")
    kotlin.sourceSets.named("commonMain") {
        kotlin.srcDir(kspOut)
    }
    listOf("macosArm64Main", "linuxX64Main", "linuxArm64Main", "mingwX64Main").forEach { name ->
        kotlin.sourceSets.findByName(name)?.let { ss ->
            val filtered = ss.kotlin.srcDirs.filter { !it.path.contains("generated/ksp") }
            if (filtered.size < ss.kotlin.srcDirs.size) ss.kotlin.setSrcDirs(filtered)
            // flatbuffers-kotlin 运行时的 expect/actual 平台实现（唯一一个平台文件）。
            ss.kotlin.srcDir("protocol/runtime/nativeMain")
        }
    }
}

// FlatBuffers 生成物不进仓(protocol/.gitignore):编译前由 protocol/scripts/generate.sh
// 现场产出 kotlin-kmp / bfbs / cpp。脚本自己钉 flatc 版本并跑语义校验。
val generateProtocol by tasks.registering(Exec::class) {
    description = "flatc: Kotlin KMP bindings, .bfbs and C++ from protocol/schemas"
    workingDir = file("protocol")
    commandLine("bash", "scripts/generate.sh")
    inputs.dir("protocol/schemas")
    inputs.dir("protocol/scripts")
    outputs.dir("protocol/generated/kotlin-kmp")
    outputs.dir("protocol/generated/bfbs")
}
tasks.matching { it.name.startsWith("compileKotlin") || it.name == "compileCommonMainKotlinMetadata" || it.name.startsWith("kspKotlin") }
    .configureEach { dependsOn(generateProtocol) }

tasks.matching { it.name == "compileCommonMainKotlinMetadata" }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
tasks.matching { it.name.matches(Regex("kspKotlin(LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}

// 把 SQL 迁移嵌进二进制：K/N 运行时不做文件 IO，迁移脚本必须编译期内联。
// @Module(migrations = true) 会去找按约定 FQN 生成的 init.generated.MmorpgMigrationResources，
// 没有这段就找不到，KSP 直接报错。
extra["neton.migration.moduleId"] = "mmorpg"
extra["neton.migration.dialects"] = listOf("postgresql")
extra["neton.migration.sqlSourceDir"] = file("sql")
apply(from = "../../Neton/neton/scripts/embed-migration-resources.gradle.kts")
