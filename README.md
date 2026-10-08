# YanMusicMobile

**YanMusic 移动端仓库** —— 本仓库从 [YanMusic](https://github.com/MmlwrYan/YanMusic) 桌面版拆分而来，只服务 Android 端，**不包含**桌面版的 Electron / Vue 代码。

> **当前版本：`v0.0.1-alpha`（最小技术 Demo / pre-release）**
> 定位：跑通「Rust → JNI → libmpv → APK」这条链路，并把构建固化进云 CI。
> **首版完整界面已入库** —— 首页 / 发现 / 音乐库 / 我的 四大页 + 全屏播放器，
> 自适应手机与平板（紧凑 / 中等 / 展开三档窗口宽度），另含诊断页。
> **仍不承诺功能完整、不承诺可日常使用；真机 GUI 冒烟未做。**

---

## 一、这是什么

一个**最小技术 Demo**，用来验证四件事：

1. **Rust → JNI 桥**能编译、能导出符号（20 个 `Java_com_yanmusic_engine_MpvEngine_nativeXxx`）
2. **libmpv 交叉编译流水线**能在云 CI 上跑通（arm64-v8a）
3. **符号表对齐**：Rust 导出符号 与 Kotlin `external fun` 声明逐项一致
4. **真机安装链路**能走通（APK 产出 → 装机 → 启动）

### 当前**不**包含（明确不承诺）

- **真机 GUI 冒烟 / 真机播放出声** —— 未做。首版界面已入库，但**只通过编译与单元测试，未经真机验证**
- **在线曲库 / 任何网络请求** —— 未接（`AndroidManifest` 中**没有** `INTERNET` 权限）。
  当前音源 = 通过系统文件选择器（SAF）导入的本地音频 + 1 首内置演示音源
- 后台播放 / 通知栏控制 / 锁屏歌词 —— 未做（无 `Service`、无 `MediaSession`；
  相关权限已在 manifest 中预留声明）
- 多 ABI 支持（`armeabi-v7a` / `x86_64`）—— 只做 `arm64-v8a`
- Gitee 镜像 / tag —— 推迟到 `v1.0.0`

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
│       │   ├── assets/demo/           # 内置演示音源（96kHz/24bit WAV，4.6 MB）
│       │   ├── java/com/yanmusic/engine/
│       │   │   ├── MpvEngine.kt           # JNI 声明（20 个 external fun）
│       │   │   ├── demo/MainActivity.kt   # 宿主 Activity（装配依赖 + SAF 导入）
│       │   │   ├── data/Music.kt          # 数据模型 + 内存仓库（无数据库）
│       │   │   ├── player/
│       │   │   │   ├── PlayerController.kt    # 播放状态机（队列/进度/模式）
│       │   │   │   └── LocalAudioResolver.kt  # content:// → /proc/self/fd/
│       │   │   └── ui/
│       │   │       ├── AppRoot.kt         # 导航栈 + 底栏/侧栏 + 迷你播放条
│       │   │       ├── Responsive.kt      # 窗口宽度分档 Compact/Medium/Expanded
│       │   │       ├── theme/Theme.kt     # 桌面端设计令牌 → Material3 主题
│       │   │       ├── components/        # 通用组件（渐变封面/歌曲行/顶栏…）
│       │   │       └── screens/           # 11 个页面（10 个文件；排行榜详情复用歌单详情页）
│       │   └── res/
│       └── test/java/com/yanmusic/engine/
│           └── AudioParamsTest.kt     # 单测：1 文件 3 个测试类，共 14 例
├── yan-engine-cabi/               # Rust：C ABI
├── yan-engine-core/               # Rust：引擎核心（mpv 封装 + 事件）
├── yan-engine-jni/                # Rust：JNI 桥（cdylib）
└── scripts/                       # 构建 / 验证 / 真机辅助脚本
    ├── apply-audio-trim.sh
    ├── check-symbol-closure.sh    # 符号级依赖闭包校验
    ├── selftest-symbol-guard.sh   # 守卫的变异自测（喂坏输入必须变红）
    ├── collect-libs.sh
    ├── verify-libs.sh
    ├── gen-demo-audio.py          # 生成内置演示音源 WAV
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
| `build-libmpv` | — | `libmpv-android-<arch>-audio.tar.gz`（`libmpv.so` + 全部 `DT_NEEDED`，含 `libc++_shared.so`）、`MANIFEST.txt` |
| `build-rust-bridge` | — | `libyan_engine_jni-android-arm64-v8a.tar.gz`（Rust JNI 库） |
| `build-apk` | **依赖 `build-libmpv` + `build-rust-bridge`** | `app-debug.apk`（**自包含**：libmpv 全部 9 个 `.so` + Rust 桥 1 个） |

`build-apk` 同时依赖两个 job 的原因：APK 既要打包 `libyan_engine_jni.so`，也要打包
`libmpv.so` 及其全部 `DT_NEEDED` 依赖 —— **否则装到真机上 `System.loadLibrary("mpv")`
必然抛 `UnsatisfiedLinkError`**（2026-10-08 查出的真实阻断级缺陷，已修复）。
打包前有一步 **fail-closed 逐库校验**（`Verify APK contains all native libraries`）：
缺任何一个 `.so` 直接失败 —— 宁可不出包，也不产出「看起来能装、装上就崩」的 APK。

### 内联校验断言（`build-rust-bridge`）

在打包上传前拦下架构错误 / 符号缺失 / 未对齐：

- **架构**：ELF `Machine` == `AArch64`
- **符号计数**：`Java_com_yanmusic_engine_MpvEngine_` 导出符号 == **20**
- **16KB 对齐**：ELF LOAD 段最小 `Align` >= **16384**（Android 15+ / Google Play 2025-11 强制）

---

## 四、本地构建

### 前置

- **NDK** —— 三处用途版本各不相同，别混：

  | 用途 | 版本 | 由谁指定 |
  |---|---|---|
  | `build-libmpv` 交叉编译 libmpv | `28.0.13004108` | workflow `env` |
  | `build-rust-bridge` 链接 JNI 桥 | `27.1.12297006` | workflow `env` |
  | **APK 打包时剥符号** | `27.1.12297006` | `app/build.gradle.kts` 的 `ndkVersion` |

  > ⚠️ 本模块**不编译任何 C/C++**，但**仍然必须装 NDK** ——
  > AGP 的 `:app:stripDebugDebugSymbols` 要用 NDK 里的 `llvm-strip`
  > 给 `jniLibs` 里的 `.so` 剥符号。漏装会在该任务处失败。
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

### 前置：libmpv 必须补齐（**仅本地构建需要**）

> ✅ **CI 产出的 APK 是自包含的** —— `build-apk` job 会自动下载 `build-libmpv` 的产物
> 并解包进 `jniLibs`，装到真机即可直接运行，**无需**手工补库。
> 下面的步骤只在**本地**跑 `./gradlew :app:assembleDebug` 时适用。

⚠️ 本地构建时若不补库，APK 仍能产出，但**装到真机后 `System.loadLibrary("mpv")` 会失败**。
必须把 `build-libmpv` job 产出的 tar 解包到 `prebuilt/arm64-v8a/`：

```bash
mkdir -p prebuilt/arm64-v8a
tar -xzf libmpv-android-arm64-audio.tar.gz -C prebuilt/arm64-v8a/
# 确认目录里不只是 libmpv.so，还有 libavcodec / libavformat / libavutil /
# libswresample / libc++_shared.so 等全部 DT_NEEDED 依赖（共 9 个 .so）
bash scripts/check-symbol-closure.sh   # 符号级闭包校验，缺符号直接报错
```

### 一条命令安装 + 启动

```bash
bash scripts/install-and-run.sh              # 打包 + 安装 + 启动 + 拉日志
bash scripts/install-and-run.sh --no-build   # 跳过重新打包
bash scripts/install-and-run.sh --logs-only  # 只看日志
```

脚本会依次：检查设备连接（区分 `unauthorized` / `offline` / 无设备）→ 检查 `libmpv.so` 齐备性 → 打包 → 安装 → 启动 → 拉 logcat（过滤 `yanmusic|yan-engine|mpv|libmpv|UnsatisfiedLink|samplerate|aaudio|ffmpeg|AndroidRuntime`）。

### 验证顺序（建议）

**底层链路（先过这三关，才说明 native 侧没问题）：**

1. `System.loadLibrary("mpv")` 不抛 `UnsatisfiedLinkError`
   → 失败**几乎必然是 libmpv 依赖库缺失**，不是符号表问题
2. `probeLibmpvApiVersion()` 返回 `>= 0`（最轻量探针：只 dlopen + 读版本）
3. `create()` 返回 `true` 且 `engineVersion()` 非空
   → 说明 20 个 JNI 符号全部解析成功

**界面链路（首版新增，须在真机上逐项确认）：**

4. 四个标签（首页 / 发现 / 音乐库 / 我的）可切换；底部迷你播放条常驻
5. 播放内置演示曲「引擎试听」：能出声、进度条走动、暂停/继续有效
6. 「导入音乐」选一个本地音频能播放
   → 验证 `content://` → `/proc/self/fd/<fd>` 这条通路（**libmpv 只吃真实路径**）
7. 诊断页三张卡：引擎状态为「就绪」、libmpv 探针返回 ✓、
   音频参数区读回**真实采样率** —— 播 96 kHz 文件若读回 48000，说明被重采样了
8. 旋转屏幕 / 平板横屏：布局应切到双栏（宽屏播放器自动变为左封面右控制）

---

## 六、许可

**GPL-3.0-or-later** —— 与上游 [YanMusic](https://github.com/MmlwrYan/YanMusic) 保持一致。

基于酷狗公开 API 与 [mpv-android](https://github.com/mpv-android/mpv-android) 的构建脚本。

---

## 七、版本

- **`v0.0.1-alpha`** —— 最小技术 Demo（已发布 pre-release）
  - ✅ Rust → JNI 桥、libmpv 交叉编译、符号表对齐、APK 构建链路
  - ✅ CI 产出**自包含 APK**（内置 libmpv 全 9 个 `.so` + Rust 桥，含 `libc++_shared.so`）
  - ❌ 未做 GUI 冒烟；`probeLibmpvApiVersion()` / `engineVersion()` 的真机返回值未验证
  - ❌ 不承诺 API 稳定、不承诺向后兼容

- **首版完整界面** —— `android-native` 分支，**尚未发版**
  - ✅ 首页 / 发现 / 音乐库 / 我的 四大页 + 全屏播放器 + 搜索 + 歌单详情 + 收藏
    + 最近播放 + 设置 + 引擎诊断，共 **11 页**（排行榜详情复用歌单详情页）
  - ✅ 响应式：紧凑（<600dp）/ 中等（600–839dp）/ 展开（≥840dp）三档；
    宽屏自动导航侧栏、播放器双栏、歌单网格自适应列数
  - ✅ 本地音频导入（SAF）、收藏、最近播放、播放模式、**搜索**（限设备内已有歌曲）；
    1 首内置演示音源
  - ✅ 14 例单元测试通过；本地 `assembleDebug` 与 CI 三 job 均绿
  - ❌ **真机 GUI 冒烟未做** —— 本节所有界面能力均**只经编译与单测，未经真机验证**
