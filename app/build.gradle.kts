plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.codex.audioroutelock"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.codex.audioroutelock"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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
