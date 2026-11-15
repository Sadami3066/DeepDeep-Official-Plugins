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
        versionCode = 6
        versionName = "1.1.0"
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
    testImplementation("io.github.sadami3066.deepdeep:plugin-api:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
}

apply(from = rootProject.file("gradle/plugin-bundle.gradle.kts"))
