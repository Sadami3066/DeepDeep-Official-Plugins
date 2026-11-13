plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.whywood.deepdeep.plugin.themes"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.whywood.deepdeep.plugin.themes"
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
    compileOnly("io.github.sadami3066.deepdeep:plugin-api:1.2.0")
}

apply(from = rootProject.file("gradle/plugin-bundle.gradle.kts"))
