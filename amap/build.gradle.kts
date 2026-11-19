plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.whywood.deepdeep.plugin.amap"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.whywood.deepdeep.plugin.amap"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "1.5.3"
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
    compileOnly("io.github.sadami3066.deepdeep:plugin-api:1.4.0")
    compileOnly("io.github.sadami3066.deepdeep:design:0.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation("io.github.sadami3066.deepdeep:plugin-api:1.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
}

apply(from = rootProject.file("gradle/plugin-bundle.gradle.kts"))
