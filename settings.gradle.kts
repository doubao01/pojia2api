rootProject.name = "modelbridge"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// 三个纯 JVM 模块可以在任何环境编译与测试
include(":spi")
include(":core")
include(":providers")

// :app 需要 Android SDK；未安装时跳过，避免拖累内核开发
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null
if (hasAndroidSdk) {
    include(":app")
}

