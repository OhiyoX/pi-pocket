plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 内置 Kotlin 支持：不再需要（也不允许）声明 org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
