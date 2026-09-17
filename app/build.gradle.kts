plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

android {
    namespace = "dev.codex.audioroutelock"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.codex.audioroutelock"
        minSdk = 26
        targetSdk = 35
        // 版本号按构建时间自动生成：LSPosed 以「模块代次」判断运行中的目标进程加载的是不是当前版本
        // （界面上的「待重启」就来自它）。版本号固定不变时，新 hooks 可能被当成旧的、多次构建产物也
        // 无法区分，所以这里每次构建都自增（秒级时间戳，单调递增且不会超过 versionCode 上限）。
        versionCode = (System.currentTimeMillis() / 1000L).toInt()
        versionName = "0.3.0+" + SimpleDateFormat("MMddHHmm", Locale.US).format(Date())
    }

    signingConfigs {
        // 签名信息来自环境变量（CI 通过 secrets 注入）。
        // 本地未设置环境变量时保持为空，release 构建结果与之前一致（未签名）。
        create("release") {
            val env = System.getenv()
            storeFile = env["KEYSTORE_FILE"]?.let { file(it) }
            storePassword = env["KEYSTORE_PASSWORD"]
            keyAlias = env["KEY_ALIAS"]
            keyPassword = env["KEY_PASSWORD"]
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 仅在提供 keystore 时签名，否则保持未签名（不破坏本地构建）
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.1")
}
