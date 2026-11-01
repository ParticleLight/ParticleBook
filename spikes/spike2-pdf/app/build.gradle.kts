plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pb.spike2"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pb.spike2"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-spike2"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("io.legere:pdfiumandroid:1.0.24")
}
