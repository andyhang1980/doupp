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
        versionCode = 7
        versionName = "3.0.1"
    }

    signingConfigs {
        create("release") {
            val ksFile = findProperty("KEYSTORE_FILE") as? String
                ?: System.getenv("KEYSTORE_FILE")
                ?: "E:\\lsposed\\doupp\\keystore_v2.jks"
            val ks = file(ksFile)
            if (ks.exists()) {
                storeFile = ks
                storePassword = (findProperty("KEYSTORE_PASSWORD") as? String
                    ?: System.getenv("KEYSTORE_PASSWORD")) ?: "DYpp_2026_K3y!x"
                keyAlias = (findProperty("KEY_ALIAS") as? String
                    ?: System.getenv("KEY_ALIAS")) ?: "wekit2"
                keyPassword = (findProperty("KEY_PASSWORD") as? String
                    ?: System.getenv("KEY_PASSWORD")) ?: "DYpp_2026_K3y!x"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksFile = findProperty("KEYSTORE_FILE") as? String
                ?: System.getenv("KEYSTORE_FILE")
                ?: "E:\\lsposed\\doupp\\keystore_v2.jks"
            if (file(ksFile).exists()) {
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

    applicationVariants.all {
        val variant = this
        variant.outputs
            .mapNotNull { it as? com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val baseName = "DYPP-${variant.versionName}"
                output.outputFileName = "$baseName.apk"
            }
    }
}

dependencies {
    // LibXposed API - compileOnly, provided at runtime by LSPosed (modern API 101)
    compileOnly(libs.libxposed.api)

    // 传统 Xposed API 82 - compileOnly，FPA/太极 等免root框架运行时提供（de.robv.android.xposed）
    compileOnly(files("libs/api-82.jar"))

    // DexKit - 运行时动态搜索类/方法，使模块抗抖音版本变动
    implementation(libs.dexkit)

    // AndroidX
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core)
    implementation(libs.preference)
}
