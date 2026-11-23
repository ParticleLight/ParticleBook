plugins {
    // AGP 9 起 Kotlin 支持内建，不再需要 kotlin.android 插件。
    id("com.android.library")
}

android {
    namespace = "app.particlebook.bench"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("io.legere:pdfiumandroid:1.0.30")
}
