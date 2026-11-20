plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.whywood.superpi.plugin.mcpuidemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.whywood.superpi.plugin.mcpuidemo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    compileOnly("io.github.sadami3066.superpi:plugin-api:2.0.1")
    compileOnly("io.github.sadami3066.superpi:design:1.0.0")
}

apply(from = rootProject.file("gradle/plugin-bundle.gradle.kts"))
