// YanMusic Android 端 —— 最小技术验证 Demo
//
// 目标：证明「自建交叉编译的 libmpv + Rust JNI 桥接」这条链路能跑通。
// 不是产品工程，是探针工程。

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
