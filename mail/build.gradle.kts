plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

android {
    namespace = "io.github.whywood.deepdeep.plugin.mail"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.whywood.deepdeep.plugin.mail"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
    compileOnly("io.github.sadami3066.deepdeep:plugin-api:1.0.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    compileOnly(platform("androidx.compose:compose-bom:2026.03.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
    implementation("org.apache.james:apache-mime4j-core:0.8.15")
    testImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    testImplementation("androidx.compose.runtime:runtime")
    testImplementation("androidx.compose.ui:ui")
    testImplementation("androidx.compose.foundation:foundation")
    testImplementation("androidx.compose.material3:material3")
    testImplementation("io.github.sadami3066.deepdeep:plugin-api:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
}

apply(from = rootProject.file("gradle/plugin-bundle.gradle.kts"))
