# YanMusicMobile

**YanMusic 移动端仓库** —— 本仓库从 [YanMusic](https://github.com/MmlwrYan/YanMusic) 桌面版拆分而来，只服务 Android 端，**不包含**桌面版的 Electron / Vue 代码。

> **当前版本：`v0.0.1-alpha`（最小技术 Demo / pre-release）**
> 定位：跑通「Rust → JNI → libmpv → APK」这条链路，并把构建固化进云 CI。
> **不承诺功能完整、不承诺可日常使用、未做 GUI 冒烟。**

---

## 一、这是什么

一个**最小技术 Demo**，用来验证四件事：

1. **Rust → JNI 桥**能编译、能导出符号（20 个 `Java_com_yanmusic_engine_MpvEngine_nativeXxx`）
2. **libmpv 交叉编译流水线**能在云 CI 上跑通（arm64-v8a）
3. **符号表对齐**：Rust 导出符号 与 Kotlin `external fun` 声明逐项一致
4. **真机安装链路**能走通（APK 产出 → 装机 → 启动）

### 当前**不**包含（明确不承诺）

- 应用在真机上能启动 / 能播放 / 能出声 —— 属 `v1.0.0` 范围
- GUI 冒烟 —— 未做
- 多 ABI 支持（`armeabi-v7a` / `x86_64`）—— 只做 `arm64-v8a`
- Gitee 镜像 / Release / tag —— 推迟到 `v1.0.0`

---

## 二、目录结构

```
YanMusicMobile/
├── .github/workflows/
│   └── build-libmpv-android.yml   # CI：三个 job（见下）
├── .cargo/config.toml             # Rust 交叉编译配置（16KB 对齐）
├── Cargo.toml                     # Rust workspace 根
├── Cargo.lock
├── build.gradle.kts               # Gradle 根工程
├── settings.gradle.kts
├── gradlew / gradlew.bat
├── app/                           # Android 应用模块
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/yanmusic/engine/
│       │   │   ├── MpvEngine.kt          # JNI 声明（20 个 external fun）
│       │   │   └── demo/                 # Demo 界面
│       │   └── res/
│       └── test/java/com/yanmusic/engine/
│           └── AudioParamsTest.kt        # 单测
├── yan-engine-cabi/               # Rust：C ABI
├── yan-engine-core/               # Rust：引擎核心（mpv 封装 + 事件）
├── yan-engine-jni/                # Rust：JNI 桥（cdylib）
└── scripts/                       # 构建 / 验证 / 真机辅助脚本
    ├── apply-audio-trim.sh
    ├── collect-libs.sh
    ├── verify-libs.sh
    ├── env-android.sh / .ps1 / .example
    ├── setup-android-sdk.sh
    └── install-and-run.sh         # 真机安装与启动
```

> ⚠️ **与旧结构的关系**：拆分前这些内容位于 YanMusic 的 `android/` 与 `scripts/android/` 下。
> 本仓库把它们**提升到库根**（`android/` 前缀已去除），因此所有脚本的路径解析都相应上跳了一级。

---

## 三、CI 触发条件

工作流文件：`.github/workflows/build-libmpv-android.yml`

| 触发方式 | 说明 |
|---|---|
| `push` 到 `android-native` 分支 | 自动验证 |
| 打 `android-native-v*` tag | 产出可归档产物 |
| `workflow_dispatch` | 手动触发，可指定 `arch` / `audio_only` / `mpv_version` |

> 注意：**刻意不加 `paths` 过滤** —— `paths` 会同时过滤 tag，导致打 tag 不触发。

### 三个 job

| job | 依赖 | 产出 |
|---|---|---|
| `build-libmpv` | — | `libmpv-android-<arch>-audio.tar.gz`（`libmpv.so` + 全部 `DT_NEEDED`）、`MANIFEST.txt` |
| `build-rust-bridge` | — | `libyan_engine_jni-android-arm64-v8a.tar.gz`（Rust JNI 库） |
| `build-apk` | **依赖 `build-rust-bridge`** | `app-debug.apk` |

`build-apk` 依赖 `build-rust-bridge` 的原因：APK 需要打包 `libyan_engine_jni.so`。

### 内联校验断言（`build-rust-bridge`）

在打包上传前拦下架构错误 / 符号缺失 / 未对齐：

- **架构**：ELF `Machine` == `AArch64`
- **符号计数**：`Java_com_yanmusic_engine_MpvEngine_` 导出符号 == **20**
- **16KB 对齐**：ELF LOAD 段最小 `Align` >= **16384**（Android 15+ / Google Play 2025-11 强制）

---

## 四、本地构建

### 前置

- **NDK r27+**（`build-rust-bridge` 用 `27.1.12297006`；`build-libmpv` 用 `28.0.13004108`）
- **Rust** + `aarch64-linux-android` target
- **Android SDK**（`platforms;android-35`、`build-tools;35.0.0`）
- **JDK 17+**

### ① 配置 NDK 环境

```bash
# Linux / macOS / Git Bash
source scripts/env-android.sh

# Windows PowerShell
. .\scripts\env-android.ps1
```

> `.cargo/config.toml` **刻意不写死 NDK 绝对路径**（CI 是 Linux、本地是 Windows）。
> linker 由环境变量 `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` 提供。
> 若该变量缺失，Cargo 会回退系统 `cc` 并**链接失败** —— 这是**有意为之**：宁可明确报错，也不要静默产出错误架构的库。

### ② 构建 Rust JNI 库

```bash
cargo build -p yan-engine-jni --target aarch64-linux-android --release
# 产物：target/aarch64-linux-android/release/libyan_engine_jni.so
```

### ③ 构建 APK

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 若 `libmpv.so` 及其依赖尚未放入 `prebuilt/arm64-v8a/`，APK 仍可构建，
> 但**安装到真机后 `System.loadLibrary("mpv")` 会失败**（见下节）。

---

## 五、真机运行

### 前置：libmpv 必须补齐

⚠️ **APK 单独安装会因缺少 `libmpv.so` 而 `loadLibrary` 失败。**
必须把 `build-libmpv` job 产出的 tar 解包到 `prebuilt/arm64-v8a/`：

```bash
mkdir -p prebuilt/arm64-v8a
tar -xzf libmpv-android-arm64-audio.tar.gz -C prebuilt/arm64-v8a/
# 确认目录里不只是 libmpv.so，还有 libavcodec / libavformat / libavutil /
# libswresample / libass 等全部 DT_NEEDED 依赖
```

### 一条命令安装 + 启动

```bash
bash scripts/install-and-run.sh              # 打包 + 安装 + 启动 + 拉日志
bash scripts/install-and-run.sh --no-build   # 跳过重新打包
bash scripts/install-and-run.sh --logs-only  # 只看日志
```

脚本会依次：检查设备连接（区分 `unauthorized` / `offline` / 无设备）→ 检查 `libmpv.so` 齐备性 → 打包 → 安装 → 启动 → 拉 logcat（过滤 `yanmusic|yan-engine|mpv|libmpv|UnsatisfiedLink|samplerate|aaudio|ffmpeg|AndroidRuntime`）。

### 验证顺序（建议）

1. `System.loadLibrary("mpv")` 不抛 `UnsatisfiedLinkError`
   → 失败**几乎必然是 libmpv 依赖库缺失**，不是符号表问题
2. `probeLibmpvApiVersion()` 返回 `>= 0`（最轻量探针：只 dlopen + 读版本）
3. `create()` 返回 `true` 且 `engineVersion()` 非空
   → 说明 20 个 JNI 符号全部解析成功

---

## 六、许可

**GPL-3.0-or-later** —— 与上游 [YanMusic](https://github.com/MmlwrYan/YanMusic) 保持一致。

基于酷狗公开 API 与 [mpv-android](https://github.com/mpv-android/mpv-android) 的构建脚本。

---

## 七、版本

- **`v0.0.1-alpha`** —— 最小技术 Demo（本版）
  - ✅ Rust → JNI 桥、libmpv 交叉编译、符号表对齐、APK 构建链路
  - ❌ 未做 GUI 冒烟；`probeLibmpvApiVersion()` / `engineVersion()` 的真机返回值未验证
  - ❌ 不承诺 API 稳定、不承诺向后兼容
