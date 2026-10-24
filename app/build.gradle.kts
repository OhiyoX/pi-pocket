import java.time.Instant

plugins {
    id("com.android.application")
    // Kotlin 由 AGP 9 内置提供；这里只补 Compose 编译器插件
    id("org.jetbrains.kotlin.plugin.compose")
}

// 构建时算版本信息（用户口径：app 要能自证是哪个提交构建的）——git 短 hash，有未提交改动就缀 -dirty。
fun gitOutput(vararg args: String): String = runCatching {
    val process = ProcessBuilder(*args).directory(rootDir).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    out
}.getOrDefault("")

val gitHash: String = gitOutput("git", "rev-parse", "--short", "HEAD").ifBlank { "nogit" } +
    (if (gitOutput("git", "status", "--porcelain").isNotBlank()) "-dirty" else "")
// 只记 **UTC 瞬时**（ISO-8601），显示时由 app 用**设备本地时区**格式化。
// 以前用 OffsetDateTime.now().take(16) 写的是**构建 JVM 的时区** —— Termux 里没设 TZ 时 Java 落回
// UTC，于是「关于」里那行时间比本地慢 8 小时（用户 2026-10-22 报「安装时间要显示本地时间」）。
val buildTime: String = Instant.now().toString()

android {
    namespace = "id.harness.pipocket"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.harness.pipocket"
        minSdk = 28
        targetSdk = 36
        versionCode = 23
        versionName = "0.4.4"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 门禁：真实错误必须为 0（CI 与本地同口径）
        abortOnError = true
        warningsAsErrors = false
        // local.properties 是本机环境文件（不入库、CI 上不存在），它的转义不影响产物
        disable += "PropertyEscape"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 前后台钩子（Connection 自恢复）：ProcessLifecycleOwner 需要这两个
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    // SSH：用来在远端机器上把桥拉起来（桥没起时 app 直接 SSH 过去启动）
    // SSH/SFTP：**sshj**（现代库，原生支持 OpenSSH 新格式密钥：ed25519 / ECDSA / 带口令的 PEM）。
    // 之前的 jsch 只认 PEM，遇到 Termux 里常见的 ed25519（-----BEGIN OPENSSH PRIVATE KEY-----）就 Auth fail。
    implementation("com.hierynomus:sshj:0.39.0")
    // sshj 的 ed25519 依赖这两个 provider（Android 上没有内置 EdDSA）
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // sshj 用 slf4j 打日志；app 里不需要输出，nop 掉即可
    implementation("org.slf4j:slf4j-nop:2.0.13")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试（纯 JVM，不依赖设备）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
}
