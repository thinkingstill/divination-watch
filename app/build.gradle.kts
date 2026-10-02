import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wearcast.liuyao"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.wearcast.liuyao"
        // 真机 OWW212 = Android 11 / API 30；minSdk 与 targetSdk 都对齐设备，行为兼容最省事
        minSdk = 30
        targetSdk = 30
        // 版本号与方案文档的修订号对齐：
        //   v0.3.0 双模式 + 国风动画 + 方表圆表适配
        //   v0.3.1 反馈修正（卜卦全程常亮 / 摇卦页卦象收窄 / 结果页不再自动下滚 / 铜钱放大）
        // versionCode 每出一次包 +1，方便 adb install -r 覆盖安装时能看出是不是新包。
        versionCode = 3
        versionName = "0.3.1"

        // 设备仅 armeabi-v7a（32 位）。本工程为纯 dex、不含 .so，此处只作意图护栏。
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 产物文件名带版本号：app-debug.apk → 金钱卦-v0.3.1-debug.apk
    // AGP 未提供改 outputFileName 的公开 API，沿用其内部实现类；这只影响产物命名，不影响打包内容。
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "金钱卦-v${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 零运行时依赖：
// 设备内存 860MB 且带 android.hardware.ram.low，AndroidX / Compose 一律不引入。
// 因此 Activity 继承 android.app.Activity，界面用原生 View + Canvas。
dependencies {
}
