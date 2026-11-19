plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.whywood.superpi.plugin.micloud"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.whywood.superpi.plugin.micloud"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "2.7.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("io.github.sadami3066.superpi:plugin-api:2.0.0")
    compileOnly("io.github.sadami3066.superpi:design:1.0.0")
    compileOnly("androidx.activity:activity-compose:1.12.4")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    compileOnly(platform("androidx.compose:compose-bom:2026.03.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.animation:animation")
    compileOnly("androidx.compose.material3:material3")
    testImplementation("io.github.sadami3066.superpi:plugin-api:2.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
}

apply(from = rootProject.file("gradle/plugin-bundle.gradle.kts"))
