plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.xposed.doupp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xposed.doupp"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // 从环境变量读取签名信息，CI 中通过 secrets 注入
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 keystore 时签名，无则用 debug 签名
            val hasKeystore = System.getenv("KEYSTORE_FILE") != null
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            res.srcDirs("src/main/res")
        }
    }
}

dependencies {
    // Xposed API - compileOnly, provided at runtime by LSPosed
    compileOnly(libs.xposed.api)

    // DexKit - 运行时动态搜索类/方法，使模块抗抖音版本变动
    implementation(libs.dexkit)

    // AndroidX
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core)
}
