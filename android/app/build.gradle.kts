plugins {
    // AGP 9 起 Kotlin 支持内建（kotlin.android 插件由 AGP 自带，重复声明会直接报错）；
    // Compose 编译器插件仍是独立插件，版本要与内建 Kotlin 对齐。
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.particlebook.android"
    compileSdk = 37
    // Compose 1.13.0 的 AAR 元数据要求至少编译到 API 37.1（SDK 侧是 platforms;android-37.1）。
    compileSdkMinor = 1
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.particlebook"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "2.2.1-p0"
        testInstrumentationRunner = "app.particlebook.android.reader.epub.EpubLayoutInstrumentation"
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
        // P0 性能探针专用：非 debuggable、debug 签名、包含 src/debug 的 M0ProbeActivity。
        // 只用于 M0 真机测量，不进入发布产物；release 仍不带探针。
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    sourceSets {
        getByName("benchmark") {
            java.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    // PDF 文本层（16KB 静态对齐；P0 由 download-ux 验证运行时）
    implementation("io.legere:pdfiumandroid:1.0.30")
    implementation(project(":bench"))

    // M3 mobile UI（L1 Compose 外壳；见 docs/android-port/android-ui-m3.md）
    // material3 1.5.0（2026-11）：统一进度组件（LinearWavyProgressIndicator /
    // CircularWavyProgressIndicator / LoadingIndicator）转正；AAR 元数据要求
    // minCompileSdk=37、minimum AGP 9.1，故工具链同步升到 AGP 9.5.2 + Kotlin 2.3.21 + Gradle 9.8.2。
    implementation("androidx.compose.material3:material3:1.5.0")
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    // JVM 单测：Gradle 9 发行版不再自带 junit jar，改用正式坐标（首次在线拉一次，之后走离线缓存）
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}