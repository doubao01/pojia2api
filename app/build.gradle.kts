plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.modelbridge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.modelbridge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    // Loomy 短信签名材料只能由构建环境注入；公开源码/CI 不保存任何生产秘密。
    // 本机可用 ~/.gradle/gradle.properties 或环境变量提供：
    // MODELBRIDGE_LOOMY_APP_ID / ACCESS_KEY / SECRET。
    fun loomy(name: String): String =
        (providers.gradleProperty("MODELBRIDGE_LOOMY_$name").orNull
            ?: providers.environmentVariable("MODELBRIDGE_LOOMY_$name").orNull
            ?: "").trim()

    listOf("debug", "release").forEach { type ->
        buildTypes.getByName(type) {
            buildConfigField("String", "LOOMY_APP_ID", "\"" + loomy("APP_ID") + "\"")
            buildConfigField("String", "LOOMY_ACCESS_KEY", "\"" + loomy("ACCESS_KEY") + "\"")
            buildConfigField("String", "LOOMY_SECRET", "\"" + loomy("SECRET") + "\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":providers"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.app)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)

    // 网关内核使用 kotlinx.coroutines，界面同样需要
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
