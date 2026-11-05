plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.particlebook.android"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.particlebook"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.2.1-p0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // PDF 文本层（16KB 静态对齐；P0 由 download-ux 验证运行时）
    implementation("io.legere:pdfiumandroid:1.0.30")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(project(":bench"))

    // M3 mobile UI（L1 Compose 外壳；见 docs/android-port/android-ui-m3.md）
    // M3 Expressive 正式版（2026-09）：material3 1.4.0 起 MotionScheme / MaterialShapes /
    // ButtonGroup / SplitButton / FABMenu / FloatingToolbar / LoadingIndicator 均为稳定 API。
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // JVM 单测：离线环境没有 junit 缓存，复用 Gradle 发行版自带 jar（实测 /g/Android/gradle-8.11.1/lib 下有 junit-4.13.2 / hamcrest-core-1.3）
    testImplementation(files("${gradle.gradleHomeDir}/lib/junit-4.13.2.jar"))
    testImplementation(files("${gradle.gradleHomeDir}/lib/hamcrest-core-1.3.jar"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}