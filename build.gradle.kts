// 顶层构建脚本：只声明插件，不引入任何第三方依赖。
// 版本组合为本机实测可离线出包的一组：
//   Gradle 9.0.0 + JDK 25 + AGP 8.13.0 + Kotlin 2.2.0
// 注意：Gradle 8.13 在 JDK 25 上会崩（JavaVersion.parse 认不出 25.x），不要降级。
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
}
