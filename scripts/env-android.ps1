# YanMusic Android 开发环境（PowerShell 版）
# 由 2026-10-04 环境搭建生成
#
# 用法（PowerShell）：
#   . .\scripts\android\env-android.ps1
# 用法（bash/Git Bash）：
#   source scripts/env-android.sh

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_SDK_ROOT = "D:\Android\Sdk"
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:ANDROID_NDK_HOME = "D:\Android\Sdk\ndk\27.1.12297006"
$env:ANDROID_NDK_VERSION = "27.1.12297006"

# 把 JDK 与 platform-tools 加进 PATH
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin;$env:PATH"

# ─────────────────────────────────────────────────────────────────
# Rust 交叉编译到 Android 所需的 linker
#
# .cargo/config.toml 里刻意不写死 NDK 路径（CI 是 Linux、
# 本地是 Windows，写死任一侧都会让另一侧失效），改由这里注入。
# 若缺失，Rust 会回退到系统 cc 并大概率链接失败。
#
# 变量命名规则（Cargo 规定）：
#   CARGO_TARGET_<TRIPLE 大写且 - 换 _>_LINKER
# ─────────────────────────────────────────────────────────────────
$ndkBin = Join-Path $env:ANDROID_NDK_HOME "toolchains\llvm\prebuilt\windows-x86_64\bin"

# minSdk 26 → 用 *26-clang 包装器（会自动带上 --target=<triple>26）
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $ndkBin "aarch64-linux-android26-clang.cmd"
$env:CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER = Join-Path $ndkBin "armv7a-linux-androideabi26-clang.cmd"
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = Join-Path $ndkBin "x86_64-linux-android26-clang.cmd"
$env:CARGO_TARGET_I686_LINUX_ANDROID_LINKER = Join-Path $ndkBin "i686-linux-android26-clang.cmd"

# NDK 的 llvm-ar 供 Cargo 的 ar 配置使用
$env:AR_aarch64_linux_android = Join-Path $ndkBin "llvm-ar.exe"
$env:AR_armv7_linux_androideabi = Join-Path $ndkBin "llvm-ar.exe"

Write-Host "YanMusic Android 环境已加载：" -ForegroundColor Cyan
Write-Host "  JAVA_HOME          = $env:JAVA_HOME"
Write-Host "  ANDROID_SDK_ROOT   = $env:ANDROID_SDK_ROOT"
Write-Host "  ANDROID_NDK_HOME   = $env:ANDROID_NDK_HOME"
Write-Host "  Rust linker (arm64) = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
