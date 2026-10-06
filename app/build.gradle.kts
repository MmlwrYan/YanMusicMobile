// app 模块 —— 最小验证 Demo
//
// ⚠️ 关键点：
//  1. jniLibs 指向 Rust 产物 + libmpv 产物所在目录
//  2. 只保留 arm64-v8a（Demo 阶段减半构建时间；产品期再加）
//  3. 不用 NDK 编译 C++（我们的 native 来自 Rust 与 CI 产出的 .so）

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yanmusic.engine.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yanmusic.engine.demo"
        minSdk = 26          // AAudio 需要 26+；libmpv 的 AAudio 输出也要求 26+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-demo"

        ndk {
            // Demo 阶段：只做 arm64。产品期加 armeabi-v7a。
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            // ── native 库来源 ──
            // Rust 桥接产物：android/build-jni/<abi>/libyan_engine_jni.so
            // libmpv 产物（CI 下载解包）：android/prebuilt/<abi>/*.so
            //
            // 两个目录都设为 jniLibs，缺失时构建不报错（只是运行时会失败），
            // 这样「还没有 .so 时也能编译 Kotlin 代码」——
            // 这正是用户要求「先在 Windows 上把 Kotlin 侧写好」的前提。
            jniLibs.srcDirs(
                "src/main/jniLibs",
                "../prebuilt",
                "../build-jni",
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // libmpv 的 .so 不要被压缩，否则加载失败
        jniLibs {
            // ⚠️ 这一行与 app/src/main/AndroidManifest.xml 的
            //    android:extractNativeLibs **强耦合**：
            //      useLegacyPackaging = false → AGP 写入 extractNativeLibs="false"
            //    若在 manifest 里显式声明 extractNativeLibs 会覆盖 AGP，
            //    二者不一致时 Gradle 会报警告（2026-10-04 实测）。
            //    因此 manifest 里刻意**不声明**该属性，由这里单点决定。
            //    要改就两处一起改。
            useLegacyPackaging = false
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    // Kotlin 标准库。KGP 2.x 起不再自动注入，必须显式声明，
    // 否则 `MpvEngine.kt` 里的 `buildString` / `coerceAtLeast` 等
    // 会在编译期报 unresolved reference。
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试（Kotlin 侧接口逻辑，不需要 .so）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
}

// ── 便利任务：把 CI 下载的 tar.gz 解包到 prebuilt/ ──
// 用法：./gradlew unpackLibmpv -PlibmpvTarball=C:/path/to/libmpv-android-arm64-audio.tar.gz
tasks.register<Copy>("unpackLibmpv") {
    val tarball = providers.gradleProperty("libmpvTarball").orNull
    if (tarball != null) {
        val archive = file(tarball)
        if (archive.exists()) {
            from(zipTree(archive))
            into(layout.projectDirectory.dir("../prebuilt"))
        }
    }
    doFirst {
        if (tarball == null) {
            logger.lifecycle("提示：未指定 -PlibmpvTarball=... ，跳过解包")
        }
    }
}
