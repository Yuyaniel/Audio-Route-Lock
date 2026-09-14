buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 内置 Kotlin（不再应用 org.jetbrains.kotlin.android），
        // 按官方说明用 buildscript classpath 提升内置的 KGP 版本，以匹配 miuix 需要的 Kotlin 2.3.21。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
