plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "com.autofill.sms"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.autofill.sms"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 本项目为纯 Java 模块，无 native 代码；此处登记 NDK 版本以备扩展
        ndkVersion = "28.2.13676358"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            // 保证 META-INF/xposed/* 被正确打进 APK
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Xposed / LSPosed 框架在运行时提供，只参与编译
    compileOnly(libs.libxposed.api)
    // 模块 App 与框架通信（读写远程配置）
    implementation(libs.libxposed.service)
}
