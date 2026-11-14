plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.whywood.deepdeep.plugin.ride"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.whywood.deepdeep.plugin.ride"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    buildTypes { release { isMinifyEnabled = false } }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("io.github.sadami3066.deepdeep:plugin-api:1.3.0")
    compileOnly("io.github.sadami3066.deepdeep:design:0.9.0")
}

apply(from = rootProject.file("gradle/plugin-bundle.gradle.kts"))
